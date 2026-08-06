package com.omnitribo.compartilhado.infra;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentação da API.
 *
 * <p>As respostas de erro são declaradas uma única vez aqui e referenciadas nos controllers por
 * {@code @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflito")}. Sem isso,
 * cada endpoint repetiria seis blocos idênticos de erro e a documentação sairia de sincronia com o
 * GlobalExceptionHandler na primeira mudança.
 */
@Configuration
public class OpenApiConfig {

  private static final String ESQUEMA_BEARER = "bearerAuth";
  private static final String TIPO_PROBLEM_JSON = "application/problem+json";

  @Bean
  public OpenAPI omniTriboOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Omni-Tribo API")
                .version("v1")
                .description(
                    "Missões sociais hiperlocais gamificadas. Erros seguem RFC 9457 "
                        + "(application/problem+json)."))
        .components(
            new Components()
                .addSecuritySchemes(
                    ESQUEMA_BEARER,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token JWT RS256 obtido em POST /api/v1/auth/login"))
                .addResponses(
                    "RequisicaoInvalida",
                    problema(
                        "Payload inválido. A propriedade 'errors' lista campo e mensagem de cada"
                            + " violação."))
                .addResponses("NaoAutenticado", problema("Token ausente, expirado ou inválido."))
                .addResponses(
                    "AcessoNegado", problema("Autenticado, mas sem autoridade sobre esta missão."))
                .addResponses(
                    "NaoEncontrado",
                    problema(
                        "Missão inexistente — ou rascunho de outro usuário, deliberadamente"
                            + " indistinguível de inexistente."))
                .addResponses(
                    "Conflito", problema("Operação incompatível com o estado atual da missão."))
                .addResponses(
                    "LimiteExcedido",
                    problema("Limite de requisições excedido. Veja o header Retry-After."))
                .addResponses(
                    "NaoImplementado",
                    problema("Contrato publicado, implementação prevista para fase futura.")));
  }

  private static ApiResponse problema(String descricao) {
    return new ApiResponse()
        .description(descricao)
        .content(
            new Content()
                .addMediaType(
                    TIPO_PROBLEM_JSON,
                    new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"))));
  }
}
