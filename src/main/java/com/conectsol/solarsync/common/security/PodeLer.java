package com.conectsol.solarsync.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Matriz RBAC (CLAUDE.md seção 4), linha "Ler": todos os papéis — o dashboard incluído
 * (decisão do usuário em 09/09/2026; antes era só GESTOR/ADMIN, via uma anotação
 * {@code @SomenteGestor} que deixou de ter uso e foi removida).
 * <p>
 * Mantida separada de {@link PodeEscrever} mesmo com a expressão idêntica: o que distingue as
 * duas é a intenção no ponto de uso, e é ela que precisa ficar legível quando a matriz voltar
 * a divergir.
 */
@Documented
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole('ANALISTA', 'GESTOR', 'ADMINISTRADOR')")
public @interface PodeLer {
}
