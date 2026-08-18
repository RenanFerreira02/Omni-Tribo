package com.omnitribo.compartilhado.dominio;

import org.springframework.http.HttpStatus;

/**
 * Requisição sintaticamente válida que viola uma regra de negócio verificável só no servidor. 422
 * Unprocessable Entity.
 *
 * <p>Distinta do 400: o corpo passou por toda a validação de Bean Validation — os campos existem,
 * os tipos batem, os limites de faixa foram respeitados. O que falha é a confrontação com estado
 * que só o servidor conhece. No check-in de F6: a coordenada é um par lat/lon perfeitamente
 * formado, mas está a 340 m de uma origem cujo raio é 50 m.
 *
 * <p>Distinta do 409: o 409 diz que a operação não cabe no estado atual do agregado e caberia em
 * outro (missão ABERTA não recebe check-in, mas EM_ANDAMENTO recebe). O 422 diz que a operação cabe
 * no estado atual e mesmo assim os dados enviados não a satisfazem — repetir a mesma requisição sem
 * mudar nada falharia de novo.
 *
 * <p>Mora em compartilhado/dominio pelo mesmo motivo de {@link TransicaoInvalidaException}: o
 * GlobalExceptionHandler está fora de com.omnitribo.missoes.. e de com.omnitribo.geolocalizacao..,
 * e a regra ArchUnit o proibiria de importar o pacote interno de qualquer módulo. Herdando de
 * DominioException, o handler existente já a mapeia pelo httpStatus, sem alteração nenhuma.
 */
public class RegraNegocioVioladaException extends DominioException {

  public RegraNegocioVioladaException(String mensagem) {
    super(HttpStatus.UNPROCESSABLE_ENTITY, mensagem);
  }
}
