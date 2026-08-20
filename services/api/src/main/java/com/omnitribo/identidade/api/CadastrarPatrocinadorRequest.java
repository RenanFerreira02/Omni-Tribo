package com.omnitribo.identidade.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/v1/admin/patrocinadores}.
 *
 * <p>Sem CNPJ, sem meio de pagamento e sem dado bancário — não é omissão, é a fronteira do escopo.
 * Onboarding financeiro com validação de CNPJ e prevenção a lavagem é produto regulado; ver a seção
 * "Fora de escopo, decidido" do CLAUDE.md.
 *
 * @param transportadoraSlug precisa casar EXATAMENTE com a chave de {@code app.webhooks.segredos},
 *     que é o que a transportadora manda em {@code X-Transportadora}. Um slug que não casa produz
 *     um patrocinador que nunca é encontrado, e toda entrega daquela transportadora cai em
 *     SEM_PATROCINIO — sintoma indistinguível de saldo zerado. O {@code Pattern} restringe ao mesmo
 *     alfabeto que o filtro normaliza (minúsculas, dígitos e hífen) para que a divergência seja
 *     impossível por caixa ou por espaço, e não só improvável.
 */
public record CadastrarPatrocinadorRequest(
    @NotBlank(message = "Nome é obrigatório") @Size(max = 100) String nome,
    @NotBlank(message = "Slug da transportadora é obrigatório")
        @Size(max = 50)
        @Pattern(regexp = "[a-z0-9-]+", message = "Slug aceita apenas minúsculas, dígitos e hífen")
        String transportadoraSlug) {}
