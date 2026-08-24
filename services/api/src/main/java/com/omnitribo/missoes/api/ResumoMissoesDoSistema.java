package com.omnitribo.missoes.api;

/**
 * Missões cujo criador é o usuário-sistema — as nascidas do webhook de entrega falida.
 *
 * <p><b>{@code criadas} NÃO precisa bater com {@code ResumoEntregasFalidas.convertidas}</b>, e a
 * primeira versão deste javadoc dizia o contrário. No banco de desenvolvimento são 4 contra 10, e a
 * diferença é legítima: as entregas falidas do seed V901 apontam para missões que a V900 criou com
 * um usuário HUMANO como criador — o seed é anterior ao usuário-sistema, que só nasceu na V21.
 * Aquelas linhas estão convertidas de verdade; o que não existe é a missão de origem SISTEMA.
 *
 * <p>Por isso o painel publica os dois números e a tela EXPLICA a diferença, em vez de escondê-la
 * numa única "convertidas". Afirmar a igualdade teria feito o quê: ou um teste vermelho por um fato
 * que não é defeito, ou — pior — um leitor concluindo que a conversão gravou pela metade.
 *
 * <p><b>{@code concluidas} é o número que o painel renomeia para "re-entrega evitada".</b> É um
 * apelido, não uma segunda medição — ver o javadoc de {@code ImpactoService} e o ADR 0029 §4.
 */
public record ResumoMissoesDoSistema(long criadas, long concluidas) {}
