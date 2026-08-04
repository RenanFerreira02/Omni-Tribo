.DEFAULT_GOAL := help

.PHONY: help up down reset logs psql seed test

help: ## Exibe esta ajuda
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

up: ## Sobe PostgreSQL+PostGIS via Docker Compose
	@echo "não implementado ainda"

down: ## Para e remove os containers
	@echo "não implementado ainda"

reset: ## Para containers, apaga volumes e sobe novamente (limpa o banco)
	@echo "não implementado ainda"

logs: ## Tail nos logs do container da API
	@echo "não implementado ainda"

psql: ## Abre psql conectado ao banco local
	@echo "não implementado ainda"

seed: ## Executa tools/seed — insere dados de teste no banco
	@echo "não implementado ainda"

test: ## Roda ./mvnw verify (backend) e npm test (mobile)
	@echo "não implementado ainda"
