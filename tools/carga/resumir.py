#!/usr/bin/env python3
"""
Resume o CSV bruto do k6 em percentis por janela de 30 s, POR CENÁRIO.

Existe porque o resumo que o k6 imprime agrega a execução inteira, e a pergunta desta fase é "onde
degrada?" — que só aparece na série temporal. Um p95 único sobre uma rampa de 5 a 80 req/s é a
média de dois regimes diferentes e não descreve nenhum dos dois.

Uso: python3 resumir.py saida/bruto.csv
"""
import csv
import sys
from collections import defaultdict

JANELA = 30  # segundos


def percentil(valores, p):
    """Percentil por interpolação linear, sobre lista JÁ ORDENADA."""
    if not valores:
        return None
    if len(valores) == 1:
        return valores[0]
    pos = (len(valores) - 1) * p
    baixo = int(pos)
    alto = min(baixo + 1, len(valores) - 1)
    fracao = pos - baixo
    return valores[baixo] * (1 - fracao) + valores[alto] * fracao


def main(caminho):
    # (cenario, janela) -> lista de durações; e contadores de status
    duracoes = defaultdict(list)
    status = defaultdict(lambda: defaultdict(int))
    t0 = None

    with open(caminho, newline='', encoding='utf-8') as f:
        for linha in csv.DictReader(f):
            if linha.get('metric_name') != 'http_req_duration':
                continue
            ts = int(linha['timestamp'])
            if t0 is None:
                t0 = ts
            cenario = linha.get('scenario') or '(sem cenário)'
            janela = (ts - t0) // JANELA
            duracoes[(cenario, janela)].append(float(linha['metric_value']))
            status[(cenario, janela)][linha.get('status', '?')] += 1

    print('# Carga por patamar de 30 s\n')
    print('Gerado por `tools/carga/resumir.py` sobre a série temporal do k6.\n')

    for cenario in sorted({c for c, _ in duracoes}):
        print(f'## `{cenario}`\n')
        print('| Janela | req | req/s | p50 (ms) | p95 (ms) | p99 (ms) | 2xx | 429 | 4xx/5xx |')
        print('|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
        janelas = sorted(j for c, j in duracoes if c == cenario)
        for j in janelas:
            v = sorted(duracoes[(cenario, j)])
            st = status[(cenario, j)]
            ok = sum(n for s, n in st.items() if s.startswith('2'))
            limitado = st.get('429', 0)
            outros = sum(n for s, n in st.items() if not s.startswith('2') and s != '429')
            print(
                f'| {j * JANELA}–{(j + 1) * JANELA}s | {len(v)} | {len(v) / JANELA:.1f} '
                f'| {percentil(v, 0.50):.1f} | {percentil(v, 0.95):.1f} '
                f'| {percentil(v, 0.99):.1f} | {ok} | {limitado} | {outros} |'
            )
        print()


if __name__ == '__main__':
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    main(sys.argv[1])
