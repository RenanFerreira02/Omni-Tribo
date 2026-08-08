# Backend

- DTOs são record. Entidade JPA nunca cruza a fronteira do controller.
- Exceção de domínio herda de DominioException e mapeia para status HTTP no handler global.
- Função PostGIS vive SÓ em compartilhado/infra/ConsultasGeoespaciais — uma classe no repositório
  inteiro (ADR 0007). Usa JdbcClient, e não @Query(nativeQuery=true), que exigiria interface ligada
  a uma @Entity. Parâmetros nomeados continuam obrigatórios; zero concatenação.
- Query nativa NÃO geoespacial vive em infra/ do módulo, com @Query(nativeQuery=true) e parâmetros
  nomeados.
- Teste de integração estende TesteIntegracaoBase (Testcontainers com postgis/postgis).
- Antes de terminar qualquer tarefa: ./mvnw verify e cole a saída.

# Economia — o que nunca vem do cliente (ADR 0009)

- Recompensa é CALCULADA pelo servidor em CalculadoraDeRecompensa (missoes/dominio, função pura) e
  congelada na criação junto com versao_formula. O DTO de criação não tem xpRecompensa nem
  tokensRecompensa. A conclusão LÊ o congelado — nunca recalcula.
- Mudou parâmetro em app.missoes.recompensa? SUBA `versao` junto. O teste dourado
  (CalculadoraDeRecompensaTest.douradoV1) falha de propósito para forçar essa decisão.
- Nenhuma missão remunera em BRL: ck_missao_economia (V15) exige valor_brl = 0 em toda categoria.
- Complexidade é derivada de peso e volume quando existem (ENTREGA e COLETA os exigem); declarada
  quando não existem (TRIBO, AJUDA). Declarar junto com peso e volume é 400.
- Toda escrita de saldo passa por LivroRazaoService. Duas invariantes DIFERENTES, e confundi-las já
  custou caro: reconciliação (ledger == projeção) e conservação (SUM(carteiras)+SUM(potes)). A
  primeira passa enquanto a segunda é violada.
- Erro sai com `type` do catálogo TipoProblema, nunca about:blank — inclusive nos filtros (401, 429),
  que não passam pelo GlobalExceptionHandler.
