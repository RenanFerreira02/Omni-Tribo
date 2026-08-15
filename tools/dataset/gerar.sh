#!/usr/bin/env bash
#
# Regenera o dataset sintético de entregas e re-treina o modelo de risco de falha.
#
# Escreve três artefatos em tools/dataset/:
#   entregas-sinteticas.csv  — as 5.000 linhas, para inspeção e para anexar ao trabalho
#   coeficientes.yml         — o bloco pronto para colar em application.yml
#   relatorio.md             — métricas, matriz de confusão e varredura de limiar
#
# QUANDO RODAR: só depois de mexer no gerador, no codificador ou nos hiperparâmetros do treinador.
# O `./mvnw verify` já re-treina a cada build e confere os coeficientes publicados — este script
# existe para REPUBLICAR quando eles mudarem de propósito, não para uso rotineiro.
#
# Se o verify quebrou em ModeloRiscoTreinoTest e a mudança foi intencional, o conserto é:
#   1. bash tools/dataset/gerar.sh
#   2. colar tools/dataset/coeficientes.yml em app.logistica.risco no application.yml
#   3. SUBIR app.logistica.risco.versao
#   4. atualizar o digest em DatasetSinteticoTest, se o dataset mudou
#   5. atualizar docs/qualidade/modelo-previsao.md com os números novos
#
# Uso:  bash tools/dataset/gerar.sh
#
# Variáveis:
#   JAVA_HOME  JDK 21. Se não estiver definida, o script tenta SDKMAN e depois /usr/lib/jvm.
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
API="$RAIZ/services/api"
SAIDA="$RAIZ/tools/dataset"

# JAVA_HOME: o `java` do PATH no Fedora é JRE-only e o Maven precisa do JDK. Mesma resolução do
# hook .claude/hooks/formatar-java.sh.
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -d "$HOME/.sdkman/candidates/java/current" ]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
  elif [ -d "/usr/lib/jvm/java-21-openjdk" ]; then
    export JAVA_HOME="/usr/lib/jvm/java-21-openjdk"
  else
    echo "ERRO: JAVA_HOME não definida e nenhum JDK 21 encontrado." >&2
    exit 1
  fi
fi

verde=$'\033[32m'; cinza=$'\033[90m'; normal=$'\033[0m'

echo "${cinza}Treinando com JAVA_HOME=$JAVA_HOME${normal}"

# O relatório é um teste guardado por propriedade de sistema: fora daqui ele fica desabilitado, para
# o CI não gastar tempo imprimindo tabelas que ninguém lê. Sem Spring e sem Testcontainers — o
# pipeline inteiro é função pura sobre uma semente fixa.
cd "$API"
saida="$(./mvnw -q -o test \
  -Dtest=RelatorioTreinoTest \
  -DrelatorioModelo=true \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dspotless.check.skip=true \
  -Dspotbugs.skip=true 2>&1)" || {
    echo "$saida" >&2
    echo "ERRO: o treino falhou. Saída completa acima." >&2
    exit 1
  }

extrair() { # $1=marcador inicial  $2=marcador final
  printf '%s\n' "$saida" | sed -n "/^=== $1 ===/,/^=== $2 ===/p" | sed '1d;$d'
}

# ── coeficientes.yml ──────────────────────────────────────────────────────────────────────────
{
  echo "# Gerado por tools/dataset/gerar.sh — NÃO edite à mão."
  echo "# Cole em app.logistica.risco no application.yml, e SUBA a versão junto."
  extrair "YAML" "RAZAO"
} > "$SAIDA/coeficientes.yml"

# ── relatorio.md ──────────────────────────────────────────────────────────────────────────────
{
  echo "# Relatório de treino — gerado por tools/dataset/gerar.sh"
  echo
  echo '```'
  extrair "DATASET" "CONVERGENCIA"
  extrair "CONVERGENCIA" "LIMIAR"
  extrair "LIMIAR" "METRICAS"
  echo '```'
  echo
  echo "## Métricas"
  echo
  echo "| Partição | Acurácia | Precisão | Recall | F2 | VP | FP | VN | FN |"
  echo "|---|---:|---:|---:|---:|---:|---:|---:|---:|"
  extrair "METRICAS" "VARREDURA DE LIMIAR (validacao)"
  echo
  echo "## Varredura de limiar (validação)"
  echo
  extrair "VARREDURA DE LIMIAR (validacao)" "COEFICIENTES"
  echo
  echo "## Coeficientes: injetado × recuperado"
  echo
  extrair "COEFICIENTES" "YAML"
} > "$SAIDA/relatorio.md"

# ── entregas-sinteticas.csv ───────────────────────────────────────────────────────────────────
# O CSV sai do próprio ArtefatosDoModelo, não de um caminho paralelo: um segundo formatador seria
# uma segunda chance de divergir do que o modelo realmente viu.
./mvnw -q -o test \
  -Dtest=ExportadorDatasetCsvTest \
  -DexportarDataset=true \
  -DcaminhoCsv="$SAIDA/entregas-sinteticas.csv" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dspotless.check.skip=true \
  -Dspotbugs.skip=true

linhas=$(($(wc -l < "$SAIDA/entregas-sinteticas.csv") - 1))

echo
echo "${verde}Pronto.${normal}"
echo "  $SAIDA/entregas-sinteticas.csv   ($linhas registros)"
echo "  $SAIDA/coeficientes.yml          (colar em application.yml)"
echo "  $SAIDA/relatorio.md              (métricas e varredura)"
echo
echo "${cinza}Lembre de subir app.logistica.risco.versao se os coeficientes mudaram.${normal}"
