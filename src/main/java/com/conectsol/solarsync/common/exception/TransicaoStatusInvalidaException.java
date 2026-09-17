package com.conectsol.solarsync.common.exception;

/**
 * Transição de status que a máquina de estados do módulo não permite. Vira 409, não 400: o
 * payload está correto, o estado atual do recurso é que não admite o movimento.
 */
public class TransicaoStatusInvalidaException extends RuntimeException {

    public TransicaoStatusInvalidaException(String entidade, Enum<?> de, Enum<?> para) {
        super("%s não pode ir de %s para %s".formatted(entidade, de, para));
    }
}
