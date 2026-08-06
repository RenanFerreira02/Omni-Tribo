package com.omnitribo.compartilhado.api;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Envelope estável de paginação.
 *
 * <p>Não serializamos Page/PageImpl do Spring Data: o formato JSON dele é interno, muda entre
 * versões (o próprio Spring Data emite warning sobre isso) e vazaria campos como "pageable" e
 * "sort.unsorted" para o app mobile, que passaria a depender deles.
 */
public record PaginaResponse<T>(
    List<T> conteudo,
    int pagina,
    int tamanho,
    long totalElementos,
    int totalPaginas,
    boolean primeira,
    boolean ultima) {

  /** List.copyOf no construtor: a lista guardada é imutável e o acessor não tem o que expor. */
  public PaginaResponse {
    conteudo = List.copyOf(conteudo);
  }

  public static <T> PaginaResponse<T> de(Page<T> page) {
    return new PaginaResponse<>(
        page.getContent(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages(),
        page.isFirst(),
        page.isLast());
  }
}
