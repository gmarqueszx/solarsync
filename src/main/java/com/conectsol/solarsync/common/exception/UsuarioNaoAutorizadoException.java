package com.conectsol.solarsync.common.exception;

import org.springframework.security.core.AuthenticationException;

/**
 * Token recusado na renovação ou na leitura. Como {@link CredenciaisInvalidasException}, a
 * mensagem é genérica de propósito: não revela qual barreira caiu (token inválido ou expirado,
 * usuário inexistente ou inativo).
 */
public class UsuarioNaoAutorizadoException extends AuthenticationException {

    public UsuarioNaoAutorizadoException() {
        super("Usuário não autorizado");
    }

    public UsuarioNaoAutorizadoException(Throwable causa) {
        super("Usuário não autorizado", causa);
    }
}
