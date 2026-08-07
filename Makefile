.DEFAULT_GOAL := help

.PHONY: help up down reset logs ps psql seed test

help: ## Exibe esta ajuda
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

# Alvo de ARQUIVO, não .PHONY — é o que dá a semântica "só roda quando falta".
#
# docker-compose.yml declara env_file: .env. Medido no Compose v5.3.1: sem o
# arquivo, `up` e `config` saem com exit 1 ("env file ... not found"); `down`,
# `logs` e `ps` funcionam, porque operam sobre containers já rotulados e não
# precisam resolver a definição do serviço. Ou seja, quem quebra num clone novo
# é justamente `make up` e `make reset`.
#
# O erro do Docker é claro, então isto não existe para decifrar mensagem: existe
# para eliminar o passo manual. Clone novo roda `make up` e sobe, sem ler doc.
# O pré-requisito está em todos os alvos por consistência — criar o .env cedo
# nunca atrapalha, e evita depender de qual subcomando resolve o quê.
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
