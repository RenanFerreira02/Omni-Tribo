package com.omnitribo.logistica.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Quantas vezes um ponto de custódia recusou encomendas, por motivo, na janela consultada.
 *
 * <p>É a resposta à pergunta que o javadoc de {@code DespachanteAlertaService.gravarPontoLotado}
 * sempre fez e que nada respondia: "um ponto que recusa encomendas com frequência é exatamente o
 * dado que justifica negociar mais capacidade ou abrir outro ponto no bairro". Antes do ADR 0033
 * esse dado existia implícito — uma linha de {@code alerta} por recusa, sem leitor nenhum, 631
 * delas numa medição de 3 minutos. Agora o alerta é uma linha por janela e a CONTAGEM vem daqui.
 *
 * <p><b>Sem coordenada, de propósito.</b> {@code PontoCustodiaResponse} traz a distância porque é
 * consultado por quem vai até lá; este é um painel de operação, e a localização do ponto não muda a
 * decisão de negociar capacidade. Traz {@code capacidade} porque ela é o número que a decisão
 * compara.
 *
 * <p><b>Duas ausências declaradas, para que ninguém as leia como esquecimento.</b>
 *
 * <p>A primeira: não há recorte por TRANSPORTADORA. Para {@code PONTO_LOTADO} isso é intencional —
 * a pergunta é sobre a capacidade do ponto, e a recusa não é atribuível a quem tentou entregar.
 * Para {@code SEM_PATROCINIO} a chave acionável realmente é a transportadora, e ela não tem
 * endpoint: fica legível no corpo do próprio alerta, que nomeia a transportadora e é deduplicado
 * por ela. A contagem por transportadora é derivável de {@code entrega_falida} e ninguém a expõe.
 *
 * <p>A segunda: este recurso é DETECTIVO e passivo, como a carta-morta da outbox (ADR 0031) e o
 * diagnóstico de pote imobilizado (ADR 0032). Nada avisa que um ponto vive lotado — alguém precisa
 * consultar.
 *
 * @param motivo PONTO_LOTADO ou SEM_PATROCINIO, os dois valores que {@code
 *     ck_entrega_falida_motivo_recusa} (V23) admite.
 * @param recusas quantas recusas no recorte. É a FREQUÊNCIA — o dado que a deduplicação do alerta
 *     deixou de guardar e que nunca deixou de existir aqui.
 */
public record RecusaPorPontoResponse(
    UUID pontoCustodiaId,
    String codigo,
    String apelido,
    int capacidade,
    String motivo,
    long recusas,
    Instant primeiraRecusa,
    Instant ultimaRecusa) {}
