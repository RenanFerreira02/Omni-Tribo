package com.omnitribo.compartilhado.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Sem Spring: a derivação de chave é função pura e o que ela garante precisa ser verificável sem
 * banco nem contexto.
 *
 * <p>O que estes testes protegem é a propriedade que impede o pior bug possível do módulo: dois
 * clientes usando a mesma {@code Idempotency-Key} e o segundo recebendo o REPLAY da operação do
 * primeiro.
 */
class ChaveIdempotenciaTest {

  private static final UUID ATOR_A = UUID.fromString("11111111-0000-0000-0000-000000000001");
  private static final UUID ATOR_B = UUID.fromString("22222222-0000-0000-0000-000000000002");
  private static final UUID MISSAO = UUID.fromString("33333333-0000-0000-0000-000000000003");

  @Test
  void mesmoMaterialProduzMesmaChave() {
    assertThat(ChaveIdempotencia.saque(ATOR_A, "abc12345"))
        .as("idempotência exige determinismo — retry precisa derivar a mesma chave")
        .isEqualTo(ChaveIdempotencia.saque(ATOR_A, "abc12345"));
  }

  @Test
  void atoresDiferentesComMesmaChaveDeClienteNaoColidem() {
    // A propriedade mais importante do arquivo. A UNIQUE de chave_idempotencia é GLOBAL: sem o ator
    // no material, o "abc12345" de A e o de B seriam a mesma linha, e o segundo saque devolveria o
    // resultado do primeiro — vazamento entre usuários e operação perdida de uma vez só.
    assertThat(ChaveIdempotencia.saque(ATOR_A, "abc12345"))
        .isNotEqualTo(ChaveIdempotencia.saque(ATOR_B, "abc12345"));
  }

  @Test
  void operacoesDiferentesComMesmaChaveDeClienteNaoColidem() {
    // Sem a operação no material, o mesmo header num saque e numa transferência colidiria, e o
    // saque viraria um no-op respondendo sucesso sem ter movido dinheiro nenhum.
    String saque = ChaveIdempotencia.saque(ATOR_A, "abc12345");
    String transferencia = ChaveIdempotencia.transferenciaEnviada(ATOR_A, "abc12345");
    String financiamento = ChaveIdempotencia.financiamento(ATOR_A, MISSAO, "abc12345");

    assertThat(saque).isNotEqualTo(transferencia).isNotEqualTo(financiamento);
    assertThat(transferencia).isNotEqualTo(financiamento);
  }

  @Test
  void pernasDaTransferenciaSaoDistintasEntreSi() {
    // Uma transferência é uma operação lógica com DUAS linhas no ledger, e a UNIQUE é de coluna
    // única: as duas pernas não podem carregar a mesma chave.
    assertThat(ChaveIdempotencia.transferenciaEnviada(ATOR_A, "abc12345"))
        .isNotEqualTo(ChaveIdempotencia.transferenciaRecebida(ATOR_A, "abc12345"));
  }

  @Test
  void financiamentosDeMissoesDiferentesNaoColidem() {
    UUID outraMissao = UUID.fromString("44444444-0000-0000-0000-000000000004");
    assertThat(ChaveIdempotencia.financiamento(ATOR_A, MISSAO, "abc12345"))
        .isNotEqualTo(ChaveIdempotencia.financiamento(ATOR_A, outraMissao, "abc12345"));
  }

  @Test
  void conclusaoDependeSoDaMissaoEDoExecutor() {
    // Sem chave de cliente: /confirmar não tem header, e a idempotência tem de valer mesmo assim.
    assertThat(ChaveIdempotencia.conclusaoMissao(MISSAO, ATOR_A))
        .isEqualTo(ChaveIdempotencia.conclusaoMissao(MISSAO, ATOR_A));
    assertThat(ChaveIdempotencia.conclusaoMissao(MISSAO, ATOR_A))
        .isNotEqualTo(ChaveIdempotencia.conclusaoMissao(MISSAO, ATOR_B));
  }

  @Test
  void chaveEhHexDe64CaracteresECabeNaColuna() {
    String chave = ChaveIdempotencia.transferenciaEnviada(ATOR_A, "abc12345");
    assertThat(chave).hasSize(64).matches("[0-9a-f]{64}");
    // A coluna é VARCHAR(100); um sha256 em hex cabe com folga e o tamanho é fixo, então nenhuma
    // chave de cliente longa demais pode estourar a coluna.
    assertThat(chave.length()).isLessThan(100);
  }

  @Test
  void nuncaGuardaAChaveCruaDoCliente() {
    // Verificação direta da regra: o valor armazenado não pode conter o que o cliente mandou.
    String chaveDoCliente = "chave-do-cliente-em-claro";
    assertThat(ChaveIdempotencia.saque(ATOR_A, chaveDoCliente)).doesNotContain(chaveDoCliente);
  }
}
