package com.conectsol.solarsync.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Matriz RBAC (CLAUDE.md seção 4), linha "Apagar", e gestão de usuários. ANALISTA não apaga
 * nada: registro errado é cancelado por status, para preservar o historico_status que alimenta
 * as métricas do gestor.
 */
@Documented
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasRole('ADMINISTRADOR')")
public @interface SomenteAdministrador {
}
