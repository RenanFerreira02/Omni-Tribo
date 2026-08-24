package com.omnitribo.carteira.api;

import com.omnitribo.carteira.dominio.TipoBeneficio;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Corpo de {@code POST /api/v1/admin/beneficios}.
 *
 * <h2>Nenhum benefício se anuncia em reais</h2>
 *
 * <p>É a regra do ADR 0009 §6 na borda HTTP, e o motivo não é estético. Um benefício anunciado como
 * "R$ 10 de desconto" por 30 tokens publica uma <b>cotação implícita</b>: quem lê descobre quantos
 * tokens valem dez reais, e a partir daí o catálogo inteiro vira tabela de câmbio. Token
 * conversível em moeda corrente É dinheiro, com KYC e enquadramento regulatório junto — e isso está
 * declarado fora de escopo.
 *
 * <p>O benefício se expressa em {@link TipoBeneficio#BEM} ("um café coado") ou {@link
 * TipoBeneficio#PERCENTUAL} ("20% na revisão"). Percentual é proporção, não o valor absoluto
 * descontado, e por isso não fixa cotação nenhuma.
 *
 * <p><b>Duas camadas, no padrão do projeto:</b> aqui a recusa amigável, com 400 e o campo apontado;
 * e {@code ck_beneficio_sem_reais} (V24) como barreira final, para que um INSERT direto no banco
 * não contorne a regra. A mesma dupla que o app já mantém em {@code
 * src/features/beneficios/catalogo.ts} e no teste de catálogo.
 *
 * <p>A regex casa {@code R$}, {@code real} e {@code reais} como palavra inteira — {@code \b} evita
 * reprovar "realmente" ou "realeza". O {@code (?i)} torna a comparação insensível a caixa; o {@code
 * (?s)} faz {@code .} casar quebra de linha, senão uma descrição multilinha escaparia da negação.
 */
public record CadastrarBeneficioRequest(
    @NotNull(message = "Parceiro é obrigatório") UUID parceiroId,
    @NotBlank(message = "Título é obrigatório")
        @Size(max = 120)
        @Pattern(
            regexp = "(?is)^(?!.*(R\\$|\\breais?\\b)).*$",
            message = "Benefício não pode ser anunciado em reais: use um BEM ou um PERCENTUAL")
        String titulo,
    @NotBlank(message = "Descrição é obrigatória")
        @Size(max = 500)
        @Pattern(
            regexp = "(?is)^(?!.*(R\\$|\\breais?\\b)).*$",
            message = "Benefício não pode ser anunciado em reais: use um BEM ou um PERCENTUAL")
        String descricao,
    @Positive(message = "Custo em tokens deve ser positivo") long custoTokens,
    @NotNull(message = "Tipo é obrigatório") TipoBeneficio tipo) {}
