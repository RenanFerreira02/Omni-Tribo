# Texto da release v1.0

> Este arquivo existe para ser **colado** no GitHub (Releases → Draft a new release, tag `v1.0`).
> Ele fica versionado para que o texto publicado tenha origem rastreável. O `gh` não está instalado
> na máquina de desenvolvimento, então a publicação é manual.

---

## Omni-Tribo v1.0 — uma entrega que falhou vira missão comunitária remunerada

App de missões sociais hiperlocais. Quando uma entrega falha, o pacote fica num ponto de custódia do
bairro e um vizinho é **remunerado** para retirá-lo — o custo do fracasso logístico vira renda
comunitária. Backend Java/Spring Boot, app React Native/Expo, PostgreSQL + PostGIS.

Projeto acadêmico FIAP — Sistemas de Informação, RM 555833. Challenge Leroy Merlin: Sociedade 5.0 e
Logística.

### O que esta versão fecha

A v1.0 marca o fechamento do **ciclo econômico**. Uma auditoria do próprio projeto tinha encontrado
uma cunhagem sem lastro: concluir uma missão de ENTREGA ou AJUDA criava token do nada, e o endpoint
de integridade respondia `integro=true` o tempo todo — corretamente, porque ele compara o saldo de
cada carteira com o histórico *dela*, e cunhar escreve os dois lados.

A correção não removeu a cunhagem; **mudou-a de lugar**. A emissão saiu do fim do ciclo, onde era
implícita e por missão, e virou um ponto único e auditável: `APORTE_PATROCINADOR`, endpoint de ADMIN,
idempotente. O resgate de benefício virou o sumidouro — debita e não credita ninguém. A invariante
passou a ser enunciável e foi medida:

> `SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` é constante **dentro do ciclo de missões**,
> nas quatro categorias, e muda nas **duas pontas**: sobe no aporte, desce no resgate.

### Números, todos de comando executado

| | |
|---|---|
| Backend — JUnit 5, Testcontainers, ArchUnit | **706 testes**, 0 falhas, 2 pulados (68 classes) |
| Mobile — Jest, RTL, MSW | **221 testes**, 17 suítes |
| Conservação, quatro categorias | **Δ=0** em todas; `integro=true` em todos os pontos |
| Carga (k6, 3 cenários × 5 min) | **14.967 requisições, 0 respostas 5xx**; radar a 74,6 req/s com p95 de 4,3 ms |
| Mutação (PIT, sem gate) | 349/494 mutantes mortos; cobertura de linha das mutadas 95% |
| Calibração do modelo de risco | erro 0,0179; Brier 0,1485 contra 0,1798 do chute constante |

`./mvnw verify` não é só teste: inclui Spotless, SpotBugs em `failOnError` e dois gates de cobertura
JaCoCo que barram o build (80% global, 85% nos pacotes `dominio`).

### O que esta versão **não** garante

Está aqui porque a alternativa — deixar o leitor descobrir sozinho — é pior:

- **Nenhuma varredura de vulnerabilidade em dependências jamais completou.** O OWASP Dependency-Check
  está configurado e ligado, mas exige chave da NVD e não tem acesso anônimo. O job do CI emite um
  aviso explícito quando a chave falta, para que "verde por não ter varrido" não se confunda com
  "verde por não ter achado".
- **A carga é de uma máquina, cinco minutos por cenário.** Não é soak, não é bancada distribuída, e o
  pool de conexões nunca chegou a ser pressionado — o rate limit barrou antes. Os números de
  desempenho do documento estratégico (< 200 ms, 1.000 TPS, SLA 99,9%) são metas herdadas, não
  medições.
- **O modelo de risco é treinado em dados sintéticos**, com correlações injetadas e documentadas.
  Nenhuma métrica publicada diz respeito a operação real.
- **ENTREGA criada por um humano ainda cunha token** — sem transportadora, não há patrocinador a
  debitar. Está declarada na linha da missão, não escondida.
- **Três armadilhas diagnosticadas seguem abertas**, cada uma por decisão de contrato pendente:
  outbox sem carta-morta, ausência de diagnóstico de pote imobilizado, e alerta de ponto lotado sem
  teto nem deduplicação.
- **Não há certificação iOS/VoiceOver**, e a passada de acessibilidade em TalkBack **não foi
  executada** — está declarada como lacuna, não como suporte.

### Rodar

```bash
bash tools/gerar-chaves-dev.sh                                            # chaves RSA (obrigatório)
make up                                                                    # banco (cria o .env sozinho)
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # API :8080
cd apps/mobile && npm install && npm start                                 # leia o QR no Expo Go
```

O caminho completo, com o que fazer quando cada passo falha, está no
[README](../README.md). Para ver o ciclo inteiro em 10 minutos:
[roteiro de demonstração](ROTEIRO-DEMO.md).

### Onde ler

- **[CHANGELOG.md](../CHANGELOG.md)** — o que entrou em cada fase, e nesta versão
- **[docs/EVOLUCAO-ARQUITETURAL.md](EVOLUCAO-ARQUITETURAL.md)** — a história do defeito econômico:
  como foi detectado, por que a reconciliação não o pegou, e a distinção entre as duas invariantes
- **[docs/evidencias/](evidencias/)** — saídas reais de medição, com uma seção do que cada uma
  **não** prova
- **[docs/adr/](adr/)** — 30 decisões, com as alternativas descartadas e o motivo real de cada recusa

---

**O projeto não acertou de primeira. Ele mediu, encontrou o próprio erro e o corrigiu — e o que
continua aberto está escrito, com o número medido do lado.**
