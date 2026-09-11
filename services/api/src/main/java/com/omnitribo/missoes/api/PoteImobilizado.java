package com.omnitribo.missoes.api;

import com.omnitribo.missoes.dominio.CategoriaMissao;
import com.omnitribo.missoes.dominio.FontePote;
import com.omnitribo.missoes.dominio.StatusMissao;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma missão não-terminal cujo pote está parado há mais que o limiar do diagnóstico.
 *
 * <p><b>Não carrega título, criador nem coordenada, e a ausência é decisão de segurança.</b> {@code
 * missao.origem_lat}/{@code origem_lon} é endereço de quem pediu a missão, e uma listagem HTTP
 * paginada é o pior lugar para esse dado — fica em log de acesso, em histórico e em qualquer proxy
 * no caminho. É o mesmo argumento que tira o {@code payload} de {@code EventoEsgotadoResponse} (ADR
 * 0031) e que faz {@code POST /logistica/previsao-falha} ser POST sem escrita. Isto é um índice de
 * diagnóstico — qual missão, quanto, parada desde quando; quem precisa do resto tem {@code GET
 * /missoes/{id}}.
 *
 * @param paradaDesde o marco de fato usado para medir, que NÃO é a mesma coluna em todo status:
 *     ABERTA mede por {@code janela_fim} e os demais por {@code estado_desde}. A distinção é a
 *     mesma de {@code RegraExpiracao.Marco} e existe porque a janela de oferta é prazo ABSOLUTO
 *     escolhido pelo criador, enquanto abandono é prazo RELATIVO ao instante em que a missão parou.
 * @param varreduraCobre se existe varredura por prazo para este status. São dois diagnósticos
 *     diferentes na mesma lista: {@code true} significa que a varredura existe e NÃO drenou a
 *     missão — o suspeito é o job, e {@code ExpiracaoMissoesJob} isola falha por item sem derrubar
 *     o lote, então o erro é silencioso; {@code false} significa que varredura nenhuma alcança este
 *     status, e a única saída depende de um humano específico aparecer.
 */
public record PoteImobilizado(
    UUID missaoId,
    StatusMissao status,
    CategoriaMissao categoria,
    FontePote fontePote,
    long poteTokens,
    long tokensRecompensa,
    Instant paradaDesde,
    boolean varreduraCobre) {}
