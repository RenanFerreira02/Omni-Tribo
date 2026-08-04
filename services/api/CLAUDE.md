# Backend

- DTOs são record. Entidade JPA nunca cruza a fronteira do controller.
- Exceção de domínio herda de DominioException e mapeia para status HTTP no handler global.
- Query nativa PostGIS vive em infra/, com @Query(nativeQuery=true) e parâmetros nomeados.
- Teste de integração estende TesteIntegracaoBase (Testcontainers com postgis/postgis).
- Antes de terminar qualquer tarefa: ./mvnw verify e cole a saída.
