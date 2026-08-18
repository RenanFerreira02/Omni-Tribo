package com.omnitribo.compartilhado.dominio;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca métodos de serviço que devem gerar um registro de auditoria automático. O AuditoriaAspecto
 * intercepta após execução bem-sucedida e persiste via AuditoriaPersistencia. Não cobre eventos de
 * autenticação (login falho, refresh) que precisam de controle fino sobre atorId nulo — esses são
 * gravados diretamente no AutenticacaoService.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditavel {
  String acao();

  String entidade();
}
