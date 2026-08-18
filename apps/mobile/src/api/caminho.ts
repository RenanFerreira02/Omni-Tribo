/**
 * Escapa um valor que vai virar SEGMENTO de caminho na URL.
 *
 * **Axios não escapa segmento de path**, ao contrário do que ele faz com `params`. Um id contendo
 * `/`, `?` ou `#` remonta a requisição contra outro endpoint — com o `Authorization: Bearer` que o
 * interceptor injeta em toda chamada. Não é execução de código, mas é entrada externa reescrevendo
 * a URL de um cliente autenticado.
 *
 * O caso real: `omnitribo://missao/<qualquer-coisa>` chega ao app por deep link, o `id` desce cru
 * até `GET /missoes/{id}`. `src/lib/deepLink.ts` já recusa o que não é UUID, mas um parâmetro pode
 * chegar por outro caminho — navegação interna, teste, código futuro. As duas defesas são
 * independentes de propósito: esta protege mesmo quando a primeira não roda.
 *
 * Use em TODO valor interpolado num template de URL. É de graça.
 */
export function seg(valor: string | number): string {
  return encodeURIComponent(String(valor));
}
