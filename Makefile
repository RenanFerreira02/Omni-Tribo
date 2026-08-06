.DEFAULT_GOAL := help

.PHONY: help up down reset logs ps psql seed test

help: ## Exibe esta ajuda
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

# Alvo de ARQUIVO, não .PHONY — é o que dá a semântica "só roda quando falta".
# docker-compose.yml declara env_file: .env, então TODO comando que lê o compose
# falha sem ele, com erro de Docker que não aponta para a causa real.
.env:
	@cp .env.example .env
	@echo ".env criado a partir de .env.example — ajuste as credenciais se necessário."

up: .env ## Sobe PostgreSQL+PostGIS via Docker Compose
	@docker compose up -d

down: .env ## Para e remove os containers (volume preservado)
	@docker compose down

reset: .env ## Destrói o volume e recria o banco do zero
	@docker compose down -v
	@docker compose up -d

logs: .env ## Tail nos logs do container do banco
	@docker compose logs -f db

ps: .env ## Lista status dos containers
	@docker compose ps

psql: .env ## Abre psql conectado ao banco local
	@docker compose exec db sh -c 'psql -U $$POSTGRES_USER $$POSTGRES_DB'

seed: ## Executa tools/seed — insere dados de teste no banco
	@echo "não implementado ainda"

test: ## Roda ./mvnw verify (backend) e npm test (mobile)
	@echo "não implementado ainda"
