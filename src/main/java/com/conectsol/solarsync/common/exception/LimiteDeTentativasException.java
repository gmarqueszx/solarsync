package com.conectsol.solarsync.common.exception;

import java.time.Duration;

/**
 * Tentativas de login demais. Vira <b>429</b> com {@code Retry-After}, e não 401: a requisição
 * nem chegou a ser avaliada, então dizer "credenciais inválidas" seria mentira — e mentira que
 * confunde justamente quem errou a senha e está tentando entender por que não entra.
 */
public class LimiteDeTentativasException extends RuntimeException {

    private final transient Duration esperar;

    public LimiteDeTentativasException(Duration esperar) {
        super("Tentativas de login demais. Tente de novo em %d minuto(s)."
                .formatted(Math.max(1, esperar.toMinutes())));
        this.esperar = esperar;
    }

    public Duration getEsperar() {
        return esperar;
    }
}
