package com.omnitribo.identidade.api;

import java.util.UUID;

/**
 * O vizinho encontrado pela busca por handle.
 *
 * <p><b>Quatro campos, e a lista curta é a decisão.</b> Não há XP, nível, e-mail, saldo nem
 * conquistas: o propósito desta resposta é deixar quem vai transferir CONFERIR que acertou a
 * pessoa, e nome mais tribo bastam para isso. Cada campo a mais seria dado pessoal entregue a quem
 * só digitou um {@code @} — e o endpoint é alcançável por qualquer membro da tribo.
 *
 * <p>A tribo vem junto porque é o que o usuário reconhece ("Marlene, da Cidade Líder"), e porque
 * confirmar o destinatário pelo NOME é a diferença entre um erro de digitação e um estorno manual
 * num ledger append-only.
 */
public record UsuarioBuscaResponse(UUID id, String handle, String nome, String tribo) {}
