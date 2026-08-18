#!/usr/bin/env bash
# Gera par de chaves RSA para uso LOCAL de desenvolvimento.
# NUNCA commite os arquivos gerados — services/api/keys/ está no .gitignore.
# Em produção, use chaves geradas fora do repositório e configure via variável de ambiente
# JWT_PRIVATE_KEY_PATH e JWT_PUBLIC_KEY_PATH.
set -euo pipefail

KEY_DIR="services/api/keys"
mkdir -p "$KEY_DIR"

if [ -f "$KEY_DIR/private.pem" ]; then
  echo "Chaves já existem em $KEY_DIR/ — nenhuma ação necessária."
  echo "Para regenerar: rm $KEY_DIR/*.pem && bash tools/gerar-chaves-dev.sh"
  exit 0
fi

# 2048 bits: recomendação NIST para horizontes até 2030 (SP 800-57).
# Em produção com ciclo de vida > 2030 ou dados de alta sensibilidade, usar 4096 bits.
openssl genrsa -out "$KEY_DIR/private.pem" 2048
openssl rsa -in "$KEY_DIR/private.pem" -pubout -out "$KEY_DIR/public.pem"

echo "Chaves RSA 2048-bit geradas:"
echo "  Privada: $KEY_DIR/private.pem"
echo "  Pública: $KEY_DIR/public.pem"
echo ""
echo "Configure as variáveis no .env (ou application-dev.yml):"
echo "  JWT_PRIVATE_KEY_PATH=keys/private.pem"
echo "  JWT_PUBLIC_KEY_PATH=keys/public.pem"
