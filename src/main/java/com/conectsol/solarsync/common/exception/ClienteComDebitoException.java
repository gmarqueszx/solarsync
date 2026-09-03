package com.conectsol.solarsync.common.exception;

/**
 * O cliente tem débito ativo, então o projeto não pode ser enviado à Coelba (etapa 2 do fluxo:
 * "sem débito, o projeto é preenchido e enviado"). Vira 409 — o payload está correto, é o
 * estado do cliente que impede a operação.
 */
public class ClienteComDebitoException extends RuntimeException {

    public ClienteComDebitoException(Long clienteId) {
        super("Cliente %d tem débito ativo; quite o débito antes de encaminhar o projeto"
                .formatted(clienteId));
    }
}
