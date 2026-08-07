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
