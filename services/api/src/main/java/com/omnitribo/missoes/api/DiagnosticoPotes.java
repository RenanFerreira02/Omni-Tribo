package com.omnitribo.missoes.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Porta de leitura sobre o token preso em missão parada — o instrumento que faltava à CONSERVAÇÃO.
 *
 * <p>Substitui, com consumidor, a {@code MissaoRepository.potesImobilizados} que existiu entre
 * 2026-08-12 e 2026-08-20 e que <b>nenhum serviço, endpoint ou teste jamais chamou</b>. Aquela
 * consulta fez o ADR 0015 registrar uma visibilidade que nunca existiu, e foi removida como órfã. A
 * regra que esta porta existe para respeitar é simples: consulta de diagnóstico sem chamador é pior
 * que lacuna declarada, porque faz a lacuna parecer coberta. Ver ADR 0032.
 *
 * <p>Dois consumidores, de propósito: {@link #resumir} atende {@code carteira}, que publica o
 * número ao lado de {@code integro} em {@code GET /admin/carteiras/reconciliacao}; {@link #listar}
 * atende o controller ADMIN deste módulo, que mostra QUAIS missões. Sem o primeiro, quem olha a
 * reconciliação continua achando que {@code integro=true} prova que nada se perdeu; sem o segundo,
 * o número não aciona ninguém.
 *
 * <p>Nenhum parâmetro de calibração cruza a porta: o limiar é conhecimento de {@code missoes} e vem
 * de {@code app.missoes.diagnostico.pote-imobilizado-apos}. Recebê-lo de fora deixaria o chamador
 * escolher o corte, e o número deixaria de significar a mesma coisa em dois lugares — mesma razão
 * pela qual {@code EstatisticasMissoes} não recebe o id do usuário-sistema.
 */
public interface DiagnosticoPotes {

  ResumoPotesImobilizados resumir();

  Page<PoteImobilizado> listar(Pageable paginacao);
}
