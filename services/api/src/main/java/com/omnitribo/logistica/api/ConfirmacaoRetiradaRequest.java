package com.omnitribo.logistica.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/v1/webhooks/transportadora/confirmacao}.
 *
 * <p>Um campo só, e é proposital: a transportadora NÃO vem daqui. Ela vem do atributo que o {@code
 * HmacWebhookFilter} publica depois de conferir a assinatura, exatamente como no webhook de entrega
 * falida. Aceitar o slug do corpo deixaria a transportadora A confirmar entrega da B — e o caminho
 * feliz continuaria funcionando, então o erro seria invisível.
 *
 * @param codigoRastreio o mesmo que a transportadora enviou quando reportou a falha. Junto com a
 *     transportadora verificada, é a chave de {@code uk_entrega_falida_rastreio}
 */
public record ConfirmacaoRetiradaRequest(
    @NotBlank(message = "Código de rastreio é obrigatório") @Size(max = 100)
        String codigoRastreio) {}
