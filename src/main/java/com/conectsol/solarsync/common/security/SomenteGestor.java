package com.conectsol.solarsync.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Matriz RBAC (CLAUDE.md seção 4), linha "Dashboard": GESTOR e ADMINISTRADOR. É a primeira
 * divergência real entre {@link PodeLer} e o resto — o dashboard mostra o desempenho de cada
 * analista, e não é informação que o próprio analista precisa ver.
 */
@Documented
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole('GESTOR', 'ADMINISTRADOR')")
public @interface SomenteGestor {
}
