package com.conectsol.solarsync.common.exception;

import org.springframework.security.core.AuthenticationException;

/**
 * Login Google recusado. Como {@link CredenciaisInvalidasException}, a mensagem é genérica de
 * propósito: não revela qual barreira caiu (token inválido, domínio não permitido, e-mail não
 * cadastrado ou usuário inativo).
 */
public class UsuarioNaoAutorizadoException extends AuthenticationException {

    public UsuarioNaoAutorizadoException() {
        super("Usuário não autorizado");
    }

    public UsuarioNaoAutorizadoException(Throwable causa) {
        super("Usuário não autorizado", causa);
    }
}
