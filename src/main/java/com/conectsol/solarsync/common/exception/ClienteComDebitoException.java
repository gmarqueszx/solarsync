package com.conectsol.solarsync.common.exception;

/**
 * O cliente tem débito ativo, então a ação está bloqueada. Vira 409 — o payload está correto,
 * é o estado do cliente que impede a operação.
 * <p>
 * Bloqueia <b>duas</b> ações, e a mensagem diz qual: enviar o projeto à Coelba (etapa 2 do
 * fluxo, "sem débito, o projeto é preenchido e enviado") e resolver a pendência (etapa 1: só se
 * resolve pendência de cliente sem débito, senão a pendência fica travada). O que a guarda
 * <b>não</b> faz é mudar status: nem o projeto nem a pendência mudam de estado por causa de
 * débito, para o cliente travado continuar visível na tela em que já estava.
 */
public class ClienteComDebitoException extends RuntimeException {

    public ClienteComDebitoException(Long clienteId, String acaoBloqueada) {
        super("Cliente %d tem débito ativo; quite o débito antes de %s"
                .formatted(clienteId, acaoBloqueada));
    }
}
