package com.omnitribo.compartilhado.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * O OpenAPI publicado descreve exatamente os endpoints que existem — nos DOIS sentidos.
 *
 * <p><b>Por que os dois sentidos.</b> Endpoint sem documentação é o app integrando contra uma
 * especificação que não o menciona; caminho documentado sem endpoint é a especificação prometendo o
 * que já foi removido. O segundo é o que envelhece calado: apagar um controller não deixa rastro na
 * documentação, e quem lê o schema descobre o buraco em produção.
 *
 * <p><b>A fonte da verdade é o {@code RequestMappingHandlerMapping}, não uma lista escrita à
 * mão.</b> Uma lista precisaria ser atualizada junto com cada endpoint novo — e a pessoa que
 * esquecesse de documentar o endpoint esqueceria da lista pela mesma razão.
 *
 * <p>O {@code @Qualifier} é obrigatório: sob {@code WebEnvironment.MOCK} a porta de gestão colapsa
 * na cadeia principal, então existe mais de um {@code HandlerMapping} no contexto. Os endpoints do
 * actuator vivem no {@code WebMvcEndpointHandlerMapping}, que é outro bean — ficam de fora por
 * construção, e não por lista de exclusão. Em produção eles nem estariam aqui: rodam na 8090, com
 * cadeia própria.
 */
class ContratoOpenApiTest extends TesteIntegracaoMvcBase {

  /** Chaves de um path item do OpenAPI que são verbos; o resto é metadado. */
  private static final Set<String> VERBOS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  /**
   * Fora do contrato de negócio: infraestrutura da própria documentação e a página de erro do Boot.
   * Não é lista de conveniência para endpoint mal documentado — é o conjunto de caminhos que não
   * pertence à API versionada.
   */
  private static final Set<String> PREFIXOS_FORA =
      Set.of("/actuator", "/v3/api-docs", "/swagger-ui", "/error");

  @Autowired private MockMvc mockMvc;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mapeamento;

  @Test
  void todo_endpoint_registrado_esta_descrito_no_openapi() throws Exception {
    SortedSet<String> semDocumentacao = new TreeSet<>(endpointsReais());
    semDocumentacao.removeAll(endpointsDocumentados());

    assertThat(semDocumentacao)
        .as(
            "endpoints que existem e o OpenAPI não descreve — o app integra contra a documentação, "
                + "então o que falta aqui é contrato invisível")
        .isEmpty();
  }

  @Test
  void todo_caminho_documentado_corresponde_a_um_endpoint_real() throws Exception {
    SortedSet<String> semEndpoint = new TreeSet<>(endpointsDocumentados());
    semEndpoint.removeAll(endpointsReais());

    assertThat(semEndpoint)
        .as(
            "caminhos descritos no OpenAPI que não existem mais — documentação que envelheceu e "
                + "promete o que a API não entrega")
        .isEmpty();
  }

  @Test
  void toda_operacao_protegida_declara_autenticacao_no_schema() throws Exception {
    // ESTA é a asserção independente do arquivo. As duas comparações de caminho acima leem, no
    // fundo, a mesma fonte que o springdoc: ele deriva os paths do RequestMappingHandlerMapping,
    // então endpoint novo nasce documentado sozinho e a comparação só pega quem for ESCONDIDO da
    // documentação (@Hidden, paths-to-exclude) — verificado na prática.
    //
    // Aqui a comparação é contra outra coisa: a CADEIA DE SEGURANÇA. Se um controller esquecer o
    // @SecurityRequirement, o schema descreve como anônimo um endpoint que responde 401 — e quem
    // integra pela documentação escreve um cliente que não funciona.
    JsonNode caminhos = JSON.readTree(schemaPublicado()).get("paths");

    SortedSet<String> semSeguranca = new TreeSet<>();
    for (Map.Entry<String, JsonNode> caminho : caminhos.properties()) {
      if (foraDoContrato(caminho.getKey()) || ehPublico(caminho.getKey())) {
        continue;
      }
      for (Map.Entry<String, JsonNode> operacao : caminho.getValue().properties()) {
        if (VERBOS.contains(operacao.getKey()) && operacao.getValue().get("security") == null) {
          semSeguranca.add(operacao.getKey().toUpperCase(Locale.ROOT) + " " + caminho.getKey());
        }
      }
    }

    assertThat(semSeguranca)
        .as(
            "operações que a cadeia de segurança exige autenticadas, mas o OpenAPI descreve como "
                + "anônimas — quem integrar pela documentação escreve um cliente que toma 401")
        .isEmpty();
  }

  @Test
  void o_schema_publicado_cobre_a_api_versionada_inteira() throws Exception {
    // Guarda contra o modo de falha silencioso dos dois testes acima: se a extração devolvesse
    // conjuntos VAZIOS — springdoc desligado, qualificador errado, filtro comendo tudo —, as duas
    // comparações passariam sem ter comparado nada.
    assertThat(endpointsReais()).hasSizeGreaterThan(30);
    assertThat(endpointsDocumentados()).hasSizeGreaterThan(30);
  }

  /** Pares "VERBO /caminho" extraídos do que o Spring realmente registrou. */
  private SortedSet<String> endpointsReais() {
    SortedSet<String> reais = new TreeSet<>();
    for (Map.Entry<RequestMappingInfo, org.springframework.web.method.HandlerMethod> entrada :
        mapeamento.getHandlerMethods().entrySet()) {

      if (!entrada.getValue().getBeanType().getPackageName().startsWith("com.omnitribo")) {
        continue;
      }
      RequestMappingInfo info = entrada.getKey();
      var condicaoCaminho = info.getPathPatternsCondition();
      if (condicaoCaminho == null) {
        continue;
      }
      var metodos = info.getMethodsCondition().getMethods();
      // Vazio significaria "todos os verbos", que o springdoc expandiria de um jeito que esta
      // comparação não prevê. Nenhum handler do projeto é assim hoje; falhar alto é melhor que
      // ignorar em silêncio se algum passar a ser.
      assertThat(metodos).as("handler sem verbo explícito: %s", entrada.getValue()).isNotEmpty();

      for (String caminho : condicaoCaminho.getPatternValues()) {
        if (foraDoContrato(caminho)) {
          continue;
        }
        for (var metodo : metodos) {
          reais.add(metodo.name() + " " + normalizar(caminho));
        }
      }
    }
    return reais;
  }

  private String schemaPublicado() throws Exception {
    return mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * Rotas que a cadeia principal declara {@code permitAll}, copiadas de {@code SecurityConfig}.
   *
   * <p>O webhook entra aqui porque {@code permitAll} ali não significa rota aberta: ele é
   * autenticado por HMAC sobre o corpo bruto, e não por JWT — logo não declara {@code bearerAuth}.
   */
  private static boolean ehPublico(String caminho) {
    return caminho.equals("/api/v1/auth/login")
        || caminho.equals("/api/v1/auth/registrar")
        || caminho.equals("/api/v1/auth/refresh")
        || caminho.equals("/api/v1/ping")
        || caminho.startsWith("/api/v1/webhooks");
  }

  /** Pares "VERBO /caminho" extraídos do schema que o springdoc publica. */
  private SortedSet<String> endpointsDocumentados() throws Exception {
    JsonNode caminhos = JSON.readTree(schemaPublicado()).get("paths");
    assertThat(caminhos).as("o schema publicado não tem a seção 'paths'").isNotNull();

    SortedSet<String> documentados = new TreeSet<>();
    // Jackson 3: properties()/propertyNames(). fields() não existe nesta major.
    for (Map.Entry<String, JsonNode> caminho : caminhos.properties()) {
      if (foraDoContrato(caminho.getKey())) {
        continue;
      }
      for (String chave : caminho.getValue().propertyNames()) {
        if (VERBOS.contains(chave)) {
          documentados.add(chave.toUpperCase(Locale.ROOT) + " " + normalizar(caminho.getKey()));
        }
      }
    }
    return documentados;
  }

  private static boolean foraDoContrato(String caminho) {
    return PREFIXOS_FORA.stream().anyMatch(caminho::startsWith);
  }

  /**
   * Tira a restrição de regex de uma variável de caminho: {@code /{id:[0-9]+}} vira {@code /{id}}.
   *
   * <p>Nenhum mapeamento do projeto usa regex hoje. Está aqui porque o dia em que alguém usar, o
   * springdoc publicaria {@code {id}} e o Spring reportaria {@code {id:[0-9]+}} — e o teste
   * acusaria, ao mesmo tempo, um endpoint sem documentação e um caminho sem endpoint, para o mesmo
   * endpoint. Um falso positivo duplo é o tipo de resultado que faz alguém desativar o teste.
   */
  private static String normalizar(String caminho) {
    return caminho.replaceAll("\\{([^:}]+):[^}]*\\}", "{$1}");
  }
}
