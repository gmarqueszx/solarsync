package com.conectsol.solarsync.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injeta o {@link UsuarioAutenticado} da requisição no parâmetro do controller. Evita
 * {@code SecurityContextHolder} espalhado — ele aparece uma única vez no projeto, dentro de
 * {@link UsuarioAutenticadoArgumentResolver}.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Autenticado {
}
