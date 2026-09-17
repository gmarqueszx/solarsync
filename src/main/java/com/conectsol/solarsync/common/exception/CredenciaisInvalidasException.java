package com.conectsol.solarsync.common.exception;

import org.springframework.security.core.AuthenticationException;

/**
 * Falha de login por senha. A mensagem é deliberadamente genérica e idêntica para todos os
 * motivos (usuário inexistente, senha errada, usuário sem senha cadastrada, usuário inativo):
 * diferenciar permitiria enumerar contas. O motivo real vai para o log, não para a resposta.
 */
public class CredenciaisInvalidasException extends AuthenticationException {

    public CredenciaisInvalidasException() {
        super("Credenciais inválidas");
    }
}
