package com.omnitribo.missoes.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta pela qual {@code logistica} transforma uma entrega falida em missão de retirada.
 *
 * <p>Existe porque o ArchUnit ({@code RegrasArquiteturaTest}) proíbe {@code logistica} de importar
 * {@code missoes.dominio}, onde vive o {@code MissaoService}. Mesmo molde de {@code
 * carteira/api/FinanciamentoMissao}: interface em {@code api/}, implementação em {@code dominio/},
 * e SÓ tipos da JDK na assinatura — um {@code CategoriaMissao} aqui já reprovaria o teste, porque a
 * regra é direcional e o chamador passaria a importar o enum.
 *
 * <p><b>Transacional.</b> Roda na transação do chamador ({@code REQUIRED}), que já segura o {@code
 * SELECT ... FOR UPDATE} do ponto de custódia. Não é escolha de estilo: a missão e o incremento da
 * ocupação precisam commitar juntos, senão existe um instante em que a encomenda ocupa vaga sem
 * missão que a retire, ou pior, uma missão publicada apontando para custódia que nada registrou.
 * {@code REQUIRES_NEW} está proibido aqui pelo mesmo motivo do resto do caminho de valor.
 */
public interface ConversaoEntregaFalida {

  /**
   * A encomenda parada no ponto, com tudo que a fórmula de recompensa precisa.
   *
   * <p>{@code valorOfertadoBrl} é INSUMO do cálculo em TOKEN e nada mais. Ele nunca chega a {@code
   * missao.valor_brl}, que {@code ck_missao_economia} trava em zero — quem cria a missão não paga,
   * e o executor recebe XP e token. Ver ADR 0009.
   *
   * <p>{@code multiplicadorRisco} e {@code faixaRisco} chegam CALCULADOS de {@code logistica}, e
   * como tipos JDK: a regra do ArchUnit é direcional, e esta porta não pode expor {@code
   * ResultadoRisco} nem {@code FaixaRisco} sem forçar {@code missoes} a importar {@code
   * logistica.dominio}. É a mesma razão pela qual {@code ConsultasGeoespaciais} recebe status como
   * String — ver ADR 0018.
   *
   * @param multiplicadorRisco multiplica a recompensa em TOKEN e é CONGELADO na missão junto com
   *     {@code versao_formula}. Nulo é tratado como 1,00 (neutro).
   * @param faixaRisco {@code BAIXO}, {@code MEDIO} ou {@code ALTO} — o {@code name()} do enum,
   *     nunca texto livre. Alimenta o aviso exibido no detalhe da missão.
   */
  record Encomenda(
      UUID entregaFalidaId,
      UUID pontoCustodiaId,
      String descricaoDoItem,
      BigDecimal origemLat,
      BigDecimal origemLon,
      BigDecimal destinoLat,
      BigDecimal destinoLon,
      String cep,
      String logradouro,
      String bairro,
      String cidade,
      String uf,
      BigDecimal pesoKg,
      BigDecimal volumeL,
      BigDecimal valorOfertadoBrl,
      BigDecimal multiplicadorRisco,
      String faixaRisco,
      UUID patrocinadorUsuarioId,
      Instant agora) {}

  /** O que a logística precisa saber de volta para fechar o registro e notificar. */
  record MissaoDeRetirada(
      UUID missaoId, int xpRecompensa, long tokensRecompensa, int nivelMinimo) {}

  /**
   * Cria a missão de retirada já ABERTA, com o pote JÁ FINANCIADO pelo patrocinador.
   *
   * <p>ABERTA, e não RASCUNHO: rascunho depende de um humano publicar, e aqui não há humano nenhum
   * no caminho — a encomenda já está fisicamente na loja quando o webhook chega.
   *
   * <p><b>Devolve VAZIO quando o patrocinador não tem saldo para o pote</b>, e nesse caso NADA é
   * criado: nem missão, nem lançamento. Vazio não é erro — é o desfecho SEM_PATROCINIO, que o
   * chamador grava na entrega falida e devolve como 200. Lançar aqui abortaria a transação e
   * apagaria justamente o registro que a transportadora precisa ler para saber que reenviar não
   * adianta; é a mesma doutrina do ponto de custódia lotado (ADR 0021).
   *
   * <p>O financiamento acontece ANTES de a missão ser gravada, e não depois: uma missão publicada
   * com pote vazio seria aceita e executada, e a conclusão falharia com 422 para sempre. Como
   * missão de retirada só conclui pela varredura de prazo, o erro nem apareceria numa requisição —
   * só no job, com o executor sem pagamento e a vaga do ponto travada.
   *
   * @param encomenda com {@code patrocinadorUsuarioId} NÃO nulo. Patrocinador ausente ou inativo é
   *     resolvido por {@code logistica} antes desta chamada, porque não faz sentido calcular
   *     recompensa para uma missão que não vai nascer.
   */
  Optional<MissaoDeRetirada> abrirMissaoDeRetirada(Encomenda encomenda);
}
