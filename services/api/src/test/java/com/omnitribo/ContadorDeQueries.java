package com.omnitribo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Conta as instruções SQL que um trecho de código emite.
 *
 * <p><b>Existe porque a defesa contra N+1 neste projeto é ARQUITETURAL e não era VERIFICADA.</b>
 * Não há uma única associação JPA no backend — toda FK é {@code UUID} escalar —, então N+1 por
 * travessia preguiçosa é impossível hoje. Mas nada falhava no dia em que alguém trocasse {@code
 * UUID criadorId} por {@code @ManyToOne Usuario criador} para expor o handle na listagem: {@code
 * MissaoService.listar} viraria 1+N e a suíte inteira continuaria verde. Este contador é a trava
 * que faltava.
 *
 * <p><b>A interceptação é no JDBC, e não no {@code StatementInspector} do Hibernate.</b> A escolha
 * não é estilística: {@code ConsultasGeoespaciais} usa {@code JdbcClient}, e toda função PostGIS do
 * sistema vive lá — um inspetor de Hibernate não enxergaria a consulta do radar, que é justamente a
 * mais quente da API. Envolvendo o {@code DataSource}, o que se conta é o que o banco recebe, venha
 * de onde vier.
 *
 * <p><b>A captura é por THREAD, e isso restringe quem pode usar.</b> Serve para {@code
 * TesteIntegracaoMvcBase} (MockMvc chama o servlet na própria thread do teste) e para chamada
 * direta a um serviço. <b>Não</b> serve para {@code TesteIntegracaoBase}, onde a requisição roda
 * numa thread do Tomcat: ali o contador devolveria zero, que é um resultado errado disfarçado de
 * bom. O preço é esse, e ele compra a ausência de estado global compartilhado entre testes.
 *
 * <p>Fora de uma captura ativa o proxy é repasse puro — nenhum teste existente muda de
 * comportamento, e o custo é uma chamada de método por instrução.
 */
public final class ContadorDeQueries {

  private static final ThreadLocal<List<String>> CAPTURA = new ThreadLocal<>();

  /** Primeira palavra-chave seguida do nome da tabela — o suficiente para agrupar por relação. */
  private static final Pattern TABELA =
      Pattern.compile("(?:from|into|update|join)\\s+([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE);

  private ContadorDeQueries() {}

  /**
   * Abre uma captura na thread atual. Use com try-with-resources — sem o fechamento, a lista vaza
   * para o teste seguinte da mesma thread e a contagem dele sai inflada.
   */
  public static Captura iniciar() {
    if (CAPTURA.get() != null) {
      throw new IllegalStateException("Já há uma captura aberta nesta thread — feche a anterior.");
    }
    List<String> instrucoes = new ArrayList<>();
    CAPTURA.set(instrucoes);
    return new Captura(instrucoes);
  }

  private static void registrar(String sql) {
    List<String> atual = CAPTURA.get();
    if (atual != null && sql != null) {
      atual.add(sql);
    }
  }

  /** O que foi capturado entre o {@code iniciar()} e o fechamento. */
  public static final class Captura implements AutoCloseable {

    private final List<String> instrucoes;

    private Captura(List<String> instrucoes) {
      this.instrucoes = instrucoes;
    }

    /** Total de instruções SQL enviadas ao banco. */
    public int total() {
      return instrucoes.size();
    }

    /** As instruções, na ordem em que saíram — para quando o número sozinho não explica. */
    public List<String> instrucoes() {
      return List.copyOf(instrucoes);
    }

    /**
     * Quantas instruções por tabela. É o que separa "duas queries" de "duas queries na mesma
     * tabela", e é essa distinção que identifica o relacionamento culpado num N+1.
     */
    public Map<String, Integer> porTabela() {
      Map<String, Integer> contagem = new TreeMap<>();
      for (String sql : instrucoes) {
        Matcher m = TABELA.matcher(sql);
        String tabela = m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "(indeterminada)";
        contagem.merge(tabela, 1, Integer::sum);
      }
      return contagem;
    }

    @Override
    public void close() {
      CAPTURA.remove();
    }
  }

  /**
   * Envolve o {@code DataSource} da aplicação. Importado por {@link TesteIntegracaoMvcBase}, então
   * todos os testes MVC compartilham UM contexto Spring — acrescentar esta configuração por classe
   * criaria uma chave de cache nova por classe, e cada contexto custa 40 conexões contra o {@code
   * max_connections=500} que {@link ContainerConfig} documenta.
   */
  @TestConfiguration
  public static class Registro implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String nome) {
      if (bean instanceof DataSource fonte && "dataSource".equals(nome)) {
        return new DataSourceContado(fonte);
      }
      // Os JdbcTemplate também, e sem isto o contador MENTE por omissão.
      //
      // Medido com uma sonda (`SELECT current_user`): na suíte, o `JdbcClient` conecta como `test`,
      // o DONO do banco — não como `omnitribo_app`. A causa é o `@Primary` de {@code
      // OperadorBancoTestConfig}: a autoconfiguração do Boot monta `NamedParameterJdbcTemplate` (e
      // daí o `JdbcClient`) a partir do `JdbcTemplate` primário, que nos testes é o do operador.
      //
      // Consequência para ESTE contador: `ConsultasGeoespaciais` — onde vive todo `ST_*` do sistema
      // — não passava pelo DataSource envolvido, e o radar aparecia com ZERO queries. Envolver o
      // DataSource de cada `JdbcTemplate` fecha o buraco. As consultas de arranjo e limpeza dos
      // testes usam o mesmo caminho, mas rodam FORA de qualquer captura, então não contaminam.
      if (bean instanceof JdbcTemplate template) {
        DataSource fonte = template.getDataSource();
        if (fonte != null && !(fonte instanceof DataSourceContado)) {
          template.setDataSource(new DataSourceContado(fonte));
        }
      }
      return bean;
    }
  }

  private static final class DataSourceContado extends DelegatingDataSource {

    DataSourceContado(DataSource alvo) {
      super(alvo);
    }

    @Override
    public Connection getConnection() throws java.sql.SQLException {
      return envolver(super.getConnection());
    }

    @Override
    public Connection getConnection(String usuario, String senha) throws java.sql.SQLException {
      return envolver(super.getConnection(usuario, senha));
    }

    private static Connection envolver(Connection real) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              new ConexaoContada(real));
    }
  }

  /**
   * Conta em {@code prepareStatement}, {@code prepareCall} e {@code createStatement}.
   *
   * <p>Contar no {@code prepare} e não no {@code execute} é a decisão que importa, e ela tem um
   * efeito conhecido: uma instrução preparada e executada em lote conta UMA vez. É o número certo
   * para a pergunta desta suíte — "quantas idas ao banco esta requisição faz?" —, e é por isso que
   * o {@code saveAll} com lote aparece melhor que N {@code save}. Quem quiser contar execuções
   * precisará de outro instrumento; este não finge medir as duas coisas.
   */
  private record ConexaoContada(Connection real) implements InvocationHandler {

    @Override
    public Object invoke(Object proxy, Method metodo, Object[] argumentos) throws Throwable {
      String nome = metodo.getName();
      if (("prepareStatement".equals(nome) || "prepareCall".equals(nome))
          && argumentos != null
          && argumentos.length > 0
          && argumentos[0] instanceof String sql) {
        registrar(sql);
      } else if ("createStatement".equals(nome)) {
        registrar("(statement sem SQL declarado)");
      }
      try {
        return metodo.invoke(real, argumentos);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }
}
