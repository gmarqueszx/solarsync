package com.conectsol.solarsync.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Matriz RBAC (CLAUDE.md seção 4), linha "Ler": todos os papéis. Mantida separada de
 * {@link PodeEscrever} mesmo com a mesma expressão — a matriz vai divergir (o dashboard é só
 * GESTOR/ADMIN) e o nome documenta a intenção no ponto de uso.
 */
@Documented
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole('ANALISTA', 'GESTOR', 'ADMINISTRADOR')")
public @interface PodeLer {
}
