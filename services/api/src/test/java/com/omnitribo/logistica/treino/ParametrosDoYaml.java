package com.omnitribo.logistica.treino;

import com.omnitribo.logistica.dominio.ParametrosRisco;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Lê {@code app.logistica.risco} do {@code application.yml} de PRODUÇÃO, sem subir contexto Spring.
 *
 * <p>Subir {@code @SpringBootTest} para conferir um arquivo de texto puxaria Testcontainers,
 * PostgreSQL e a aplicação inteira — dezenas de segundos para ler dez linhas de YAML. O binder
 * sozinho faz isso em milissegundos, e o mais importante: lê <b>o arquivo real</b>, não uma cópia
 * de teste. Se lesse uma cópia, o teste concordaria com qualquer divergência entre o que foi
 * treinado e o que a aplicação de fato carrega — que é precisamente o defeito que ele existe para
 * pegar.
 */
public final class ParametrosDoYaml {

  private ParametrosDoYaml() {}

  public static ParametrosRisco carregar() {
    StandardEnvironment ambiente = new StandardEnvironment();
    try {
      new YamlPropertySourceLoader()
          .load("application.yml", new ClassPathResource("application.yml"))
          .forEach(fonte -> ambiente.getPropertySources().addLast(fonte));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Não foi possível ler application.yml do classpath", e);
    }
    return Binder.get(ambiente)
        .bind("app.logistica.risco", ParametrosRisco.class)
        .orElseThrow(
            () -> new IllegalStateException("app.logistica.risco ausente do application.yml"));
  }
}
