.DEFAULT_GOAL := help

.PHONY: help demo feira preparar up down reset logs ps psql test

# Todo alvo de compose passa por este wrapper: ele escolhe docker ou podman, porque o CLI do docker
# pode sobreviver ao Docker Desktop desinstalado apontando para um socket morto.
COMPOSE := bash tools/demo/compose.sh

help: ## Exibe esta ajuda
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

# Alvo de ARQUIVO: só roda quando falta. Sem .env, `compose up` sai com exit 1.
.env:
	@cp .env.example .env
	@echo ".env criado a partir de .env.example."

# Prepara do zero. NÃO sobe backend nem Metro: processo longo dentro do make perde o terminal
# próprio e mistura os logs. Os alvos preparam e IMPRIMEM os comandos que faltam.
preparar: .env
	@# Recusa antes de destruir: um backend de pé não remigra (o Flyway só roda no boot), e recriar
	@# o volume por baixo dele o deixa ligado a um banco sem schema — login que não responde.
	@if (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; then \
	  echo "  x  há algo escutando na 8080 — provavelmente o backend. Pare-o antes."; \
	  echo "     Quem está na porta:  ss -lptn 'sport = :8080'"; \
	  exit 1; \
	fi
	@echo "==> 1/3  Chaves RSA"
	@bash tools/gerar-chaves-dev.sh
	@echo ""
	@echo "==> 2/3  Banco do zero"
	@$(MAKE) --no-print-directory reset
	@echo ""
	@echo "==> 3/3  Esperando o banco aceitar conexão"
	@# Consulta por TCP, não pg_isready no socket: durante o initdb o socket unix já atende e a
	@# porta 5432 ainda não, e é por TCP que o Flyway vai conectar.
	@tentativa=0; \
	 until $(COMPOSE) exec -T db \
	     sh -c 'psql -h 127.0.0.1 -U $$POSTGRES_USER -d $$POSTGRES_DB -tAc "SELECT 1"' \
	     >/dev/null 2>&1; do \
	   tentativa=$$((tentativa + 1)); \
	   if [ $$tentativa -ge 90 ]; then \
	     printf '\r  x  o banco não aceitou conexão em 90 s. Veja: make logs\n'; \
	     exit 1; \
	   fi; \
	   printf '\r  .. aguardando (%ss)' "$$tentativa"; \
	   sleep 1; \
	 done; \
	 printf '\r  ok  banco aceitando conexão (%ss)     \n' "$$tentativa"
	@echo ""

demo: preparar ## Recria chaves e banco do zero e imprime os comandos para subir API e Metro
	@echo "Falta você, em DOIS terminais:"
	@echo "   1   cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev"
	@echo "   2   cd apps/mobile && npm start"
	@echo ""
	@echo "Depois:  bash tools/demo/checar-ambiente.sh   (espere todos os ✓)"

feira: preparar ## Estande do FIAP NEXT: banco do zero e os comandos dos três terminais e do telão
	@echo "Estande pronto para subir. Em TRÊS terminais (roteiro completo em docs/FEIRA.md):"
	@echo "   1   cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,feira"
	@echo "   2   cd apps/mobile && npm start"
	@echo "   3   bash tools/feira/povoar.sh && bash tools/feira/entrega.sh"
	@echo ""
	@echo "No estande, com o celular junto ao PC:"
	@echo "   bash tools/demo/ponto-aqui.sh <LAT> <LON>   (move o ponto do estande para onde você está)"
	@echo "   bash tools/demo/checar-ambiente.sh         (espere todos os ✓)"
	@echo ""
	@echo "Telão: http://localhost:8081 no navegador, entre como admin@omnitribo.dev e abra"
	@echo "       http://localhost:8081/impacto?telao=1   (não recarregue: na web recarregar desloga)"

up: .env ## Sobe PostgreSQL+PostGIS
	@$(COMPOSE) up -d

down: .env ## Para os containers (volume preservado)
	@$(COMPOSE) down

reset: .env ## Destrói o volume e recria o banco (o seed volta no próximo boot da API)
	@$(COMPOSE) down -v
	@$(COMPOSE) up -d

logs: .env ## Tail nos logs do banco
	@$(COMPOSE) logs -f db

ps: .env ## Status dos containers
	@$(COMPOSE) ps

psql: .env ## Abre psql no banco local
	@$(COMPOSE) exec db sh -c 'psql -U $$POSTGRES_USER $$POSTGRES_DB'

test: ## ./mvnw verify (backend) + npm test (mobile)
	@echo "==> Backend: ./mvnw verify"
	@cd services/api && ./mvnw verify
	@echo "==> Mobile: npm test"
	@cd apps/mobile && npm test
