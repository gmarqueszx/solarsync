package com.conectsol.solarsync.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Matriz RBAC (CLAUDE.md seção 4), linha "Usuário / papéis": ADMINISTRADOR e GESTOR.
 * <p>
 * O GESTOR entrou aqui em 16/09/2026, quando o login Google saiu: sem auto-cadastro, cadastrar
 * gente deixou de ser tarefa eventual de configuração e virou trabalho de operação — e deixá-la
 * só com o ADMINISTRADOR faria toda contratação esperar por uma pessoa.
 * <p>
 * Não cobre a <b>exclusão</b> de usuário, que continua {@link SomenteAdministrador}: apagar quem
 * já aparece no histórico é o caminho que destrói auditoria, e o normal é desativar.
 */
@Documented
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole('GESTOR', 'ADMINISTRADOR')")
public @interface GerenciaUsuarios {
}
