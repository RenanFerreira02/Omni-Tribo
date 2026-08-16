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

# Não há script de seed, e não é esquecimento: os dados de demonstração são
# migrations Flyway na faixa 900+ (db/seed), que os perfis dev e test incluem em
# `flyway.locations`. Ou seja, o seed já roda sozinho no boot da aplicação — um
# alvo que reinserisse os mesmos dados por fora colidiria com as chaves que a
# própria migration gravou. Recarregar é `make reset`, que destrói o volume e
# deixa o Flyway reconstruir schema e seed na ordem correta.
seed: ## Explica como recarregar os dados de demonstração
	@echo "O seed não é um passo manual: db/seed/V900+ são migrations Flyway, e"
	@echo "os perfis dev e test já as aplicam no boot (flyway.locations)."
	@echo "Para recarregar do zero:  make reset"

test: ## Roda ./mvnw verify (backend) e npm test (mobile)
	@echo "==> Backend: ./mvnw verify"
	@cd services/api && ./mvnw verify
	@echo "==> Mobile: npm test"
	@cd apps/mobile && npm test
