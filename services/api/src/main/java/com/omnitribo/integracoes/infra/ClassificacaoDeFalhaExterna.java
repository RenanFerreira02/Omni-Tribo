package com.omnitribo.integracoes.infra;

import com.omnitribo.integracoes.api.ServicoExternoIndisponivelException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Decide o significado de uma falha de chamada externa. Duas perguntas, respostas diferentes.
 *
 * <p><b>Por que a classificação mora aqui e não dentro do disjuntor:</b> ela tem TRÊS consumidores
 * — a política de retry, o contador do disjuntor e o log — e as respostas não coincidem. Um 429 não
 * deve ser repetido (repetir piora) mas conta contra a saúde do provedor; um 404 não é nenhuma das
 * duas coisas. Espalhar essas regras pelos dois lugares faria as duas versões divergirem na
 * primeira manutenção.
 *
 * <p><b>O caso mais importante não aparece aqui, e é proposital.</b> O {@code {"erro":true}} do
 * ViaCEP e o bloco {@code current} ausente do Open-Meteo chegam como HTTP <b>200</b>: para esta
 * classe eles são SUCESSO, e a checagem deles acontece FORA da chamada protegida, nos clientes. Ver
 * o javadoc de {@link ClienteViaCep} — mover aquela checagem para dentro da região protegida faria
 * cinco usuários digitando CEP errado abrirem o disjuntor de um provedor saudável.
 */
final class ClassificacaoDeFalhaExterna {

  private ClassificacaoDeFalhaExterna() {}

  /**
   * Repetir tem chance real de mudar o resultado?
   *
   * <p>É a pergunta do retry, e a resposta é conservadora de propósito: só o que é comprovadamente
   * transitório entra. Timeout e 5xx entram; qualquer outra coisa fica de fora, inclusive erro de
   * desserialização — se o provedor mudou o JSON, repetir só gasta o dobro do tempo para falhar
   * igual.
   */
  static boolean eTransitoria(Throwable t) {
    // Recusa do nosso próprio bulkhead ou do disjuntor. Repetir seria empurrar contra uma porta que
    // nós mesmos fechamos, e ainda por cima segurando a thread que a porta existe para liberar.
    if (t instanceof ServicoExternoIndisponivelException) {
      return false;
    }
    // Timeout de conexão ou de leitura, DNS, socket. É o modo de falha número um com PT2S.
    if (t instanceof ResourceAccessException) {
      return true;
    }
    // Cobre HttpServerErrorException E UnknownHttpStatusCodeException (status fora do enum padrão):
    // perguntar ao status é o que evita o buraco de um 5xx não padronizado escapar da checagem.
    if (t instanceof RestClientResponseException r) {
      return r.getStatusCode().is5xxServerError();
    }
    return false;
  }

  /**
   * Conta contra a SAÚDE do provedor, para efeito de abrir o disjuntor?
   *
   * <p>Superconjunto de {@link #eTransitoria}: acrescenta o 429. Um provedor que nos limita está
   * dizendo, no protocolo dele, que não quer mais tráfego nosso — parar de chamar é exatamente a
   * reação certa, ainda que repetir a mesma requisição seja exatamente a errada.
   *
   * <p><b>4xx não entra</b>, e a razão é que 4xx é problema NOSSO: URL errada, parâmetro inválido,
   * contrato mudado. Contá-lo transformaria um defeito nosso em "provedor fora do ar" e o
   * esconderia atrás de um 503 — o disjuntor viraria uma máquina de mascarar bug.
   */
  static boolean indicaProvedorDoente(Throwable t) {
    if (eTransitoria(t)) {
      return true;
    }
    return t instanceof RestClientResponseException r && r.getStatusCode().value() == 429;
  }
}
