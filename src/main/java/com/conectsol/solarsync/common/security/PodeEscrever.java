package com.conectsol.solarsync.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/** Matriz RBAC (CLAUDE.md seção 4), linha "Criar / Editar": ANALISTA, GESTOR e ADMINISTRADOR. */
@Documented
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole('ANALISTA', 'GESTOR', 'ADMINISTRADOR')")
public @interface PodeEscrever {
}
