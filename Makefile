.DEFAULT_GOAL := help

.PHONY: help demo up down reset logs ps psql seed test

# Todo alvo de compose passa por este wrapper, e não por `docker compose` direto: nesta máquina o
# CLI do Docker sobrevive ao Docker Desktop desinstalado e continua apontando para um socket morto,
# enquanto o banco roda sob podman. O script resolve isso num lugar só — ver o cabeçalho dele.
COMPOSE := bash tools/demo/compose.sh

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

# `make demo` é o preparo COMPLETO da apresentação num comando. Cada passo trata uma armadilha
# concreta da sequência manual que ele substitui, e o comentário de cada um diz qual.
#
# NÃO sobe backend nem Metro daqui, de propósito: processo de longa duração dentro do make perde o
# terminal próprio, um Ctrl-C mata a árvore inteira, e o log dos dois sai entrelaçado no mesmo
# fluxo. O alvo prepara o ambiente e IMPRIME os dois comandos que faltam.
demo: .env ## Prepara a demonstração do zero e imprime os dois comandos restantes
	@# Recusa ANTES de destruir qualquer coisa. Um backend já de pé não remigra: o Flyway roda no
	@# boot, então recriar o volume por baixo dele o deixa ligado a um banco VAZIO. Observado: 3
	@# tabelas em `public`, login devolvendo 000, e o checar-ambiente dando sete ✓ — porque
	@# /api/v1/ping não toca o banco. Falhar aqui é a única forma de o erro não ficar invisível.
	@if (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; then \
	  echo "  x  há algo escutando na 8080 — provavelmente o backend."; \
	  echo ""; \
	  echo "     Pare-o antes: recriar o banco por baixo de um backend de pé o deixa ligado a"; \
	  echo "     um banco sem schema, e o sintoma (login que não responde) não aponta para cá."; \
	  echo ""; \
	  echo "     Quem está na porta:  ss -lptn 'sport = :8080'"; \
	  exit 1; \
	fi
	@echo "==> 1/4  Chaves RSA"
	@bash tools/gerar-chaves-dev.sh
	@echo ""
	@echo "==> 2/4  Banco do zero"
	@# `reset` e não `up`: o ensaio DERIVA o seed em silêncio. Medido num banco com 6 h de ensaio,
	@# contra o que o V905 semeia: a carteira de transportadora-dev tinha caído de 5000 para 212
	@# tokens e o Leroy Aricanduva estava 60/60. A 212 tokens e ~66 por conversão restavam ~3
	@# rodadas antes de o caminho feliz do webhook virar SEM_PATROCINIO na frente da banca — e o
	@# desfecho correto pareceria defeito.
	@$(MAKE) --no-print-directory reset
	@echo ""
	@echo "==> 3/4  Esperando o banco aceitar conexão"
	@# `up -d` volta assim que o container é CRIADO, e o healthcheck do compose declara
	@# start_period de 30 s. Sem esta espera o spring-boot:run pega o Postgres ainda subindo e
	@# morre no Flyway — com um erro de datasource que não menciona em lugar nenhum que o banco
	@# não estava pronto.
	@#
	@# A sonda é uma CONSULTA POR TCP, e não `pg_isready` no socket unix, porque o socket mente
	@# durante o initdb: o entrypoint do Postgres sobe um servidor temporário com
	@# listen_addresses='' para rodar os scripts de init, e ele já atende no socket unix enquanto
	@# a porta 5432 ainda não existe. Medido num volume recriado: socket pronto em 2 s, consulta
	@# por TCP em 3 s — 1 s de janela em que a sonda antiga dizia "pronto" e a aplicação, que
	@# conecta por TCP, ainda não conseguiria. `SELECT 1` também exige autenticação e o database
	@# criado, que é exatamente o que o Flyway vai pedir no passo seguinte.
	@tentativa=0; \
	 until $(COMPOSE) exec -T db \
	     sh -c 'psql -h 127.0.0.1 -U $$POSTGRES_USER -d $$POSTGRES_DB -tAc "SELECT 1"' \
	     >/dev/null 2>&1; do \
	   tentativa=$$((tentativa + 1)); \
	   if [ $$tentativa -ge 90 ]; then \
	     printf '\r  x  o banco não aceitou conexão em 90 s.        \n'; \
	     echo "     Veja o que ele diz:  make logs"; \
	     exit 1; \
	   fi; \
	   printf '\r  .. aguardando (%ss)' "$$tentativa"; \
	   sleep 1; \
	 done; \
	 printf '\r  ok  banco aceitando conexão (%ss)     \n' "$$tentativa"
	@echo ""
	@echo "==> 4/4  Falta você, em DOIS terminais separados:"
	@echo ""
	@echo "   terminal 1   cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev"
	@echo "   terminal 2   cd apps/mobile && npm start"
	@echo ""
	@echo "   Estado esperado quando os dois subirem:"
	@echo "     backend  'Started ApiApplication' e as 7 migrations de seed (V900-V906) aplicadas"
	@echo "     backend  API na 8080; actuator na 8090, NAO na 8081, que e do Metro"
	@echo "     Metro    QR na tela, pronto para o Expo Go"
	@echo ""
	@echo "   E entao, antes de entrar na sala:"
	@echo "     bash tools/demo/checar-ambiente.sh    # espere TODOS os ✓"
	@echo ""

up: .env ## Sobe PostgreSQL+PostGIS via Docker Compose
	@$(COMPOSE) up -d

down: .env ## Para e remove os containers (volume preservado)
	@$(COMPOSE) down

reset: .env ## Destrói o volume e recria o banco do zero
	@$(COMPOSE) down -v
	@$(COMPOSE) up -d

logs: .env ## Tail nos logs do container do banco
	@$(COMPOSE) logs -f db

ps: .env ## Lista status dos containers
	@$(COMPOSE) ps

psql: .env ## Abre psql conectado ao banco local
	@$(COMPOSE) exec db sh -c 'psql -U $$POSTGRES_USER $$POSTGRES_DB'

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
