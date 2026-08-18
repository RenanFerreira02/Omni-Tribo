package com.omnitribo.integracoes.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Endereço resolvido a partir do CEP.
 *
 * <p>Os nomes dos campos são os do NOSSO domínio — {@code cidade}, e não {@code localidade} do
 * provedor. A tradução acontece na borda, para que trocar de provedor não obrigue o app a mudar.
 *
 * <p>Isto é conveniência de preenchimento, não fonte de verdade: o endereço que vale é o que o
 * usuário confirmou e que viaja no corpo da criação da missão, validado lá.
 */
@Schema(description = "Endereço resolvido por CEP, para preencher o formulário")
public record EnderecoResponse(
    @Schema(example = "01001000", description = "8 dígitos, sem hífen") String cep,
    String logradouro,
    String bairro,
    String cidade,
    @Schema(example = "SP") String uf) {}
