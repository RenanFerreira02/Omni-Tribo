.DEFAULT_GOAL := help

.PHONY: help up down reset logs ps psql seed test

help: ## Exibe esta ajuda
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

up: ## Sobe PostgreSQL+PostGIS via Docker Compose
	@docker compose up -d

down: ## Para e remove os containers (volume preservado)
	@docker compose down

reset: ## Destrói o volume e recria o banco do zero
	@docker compose down -v
	@docker compose up -d

logs: ## Tail nos logs do container do banco
	@docker compose logs -f db

ps: ## Lista status dos containers
	@docker compose ps

psql: ## Abre psql conectado ao banco local
	@docker compose exec db sh -c 'psql -U $$POSTGRES_USER $$POSTGRES_DB'

seed: ## Executa tools/seed — insere dados de teste no banco
	@echo "não implementado ainda"

test: ## Roda ./mvnw verify (backend) e npm test (mobile)
	@echo "não implementado ainda"
