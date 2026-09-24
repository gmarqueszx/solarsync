package com.conectsol.solarsync.common.exception;

import com.conectsol.solarsync.cliente.MotivoPrioridade;

/**
 * Prioridade por instalação adiantada sem a data em que o cliente foi instalado. Vira 409: o
 * payload está bem formado, é a combinação de campos que não fecha.
 * <p>
 * A data só é obrigatória neste motivo, e é por um motivo prático: ela não fica no cliente, ela
 * desce para {@code projeto.data_instalacao} e é o que destrava a solicitação da vistoria no fim
 * do fluxo. Aceitar o pedido sem ela guardaria uma prioridade que não se completa.
 */
public class PrioridadeSemInstalacaoException extends RuntimeException {

    public PrioridadeSemInstalacaoException(Long clienteId, MotivoPrioridade motivo) {
        super(("Prioridade por %s no cliente %d exige a data em que ele foi instalado — "
                + "é ela que a etapa de vistoria usa")
                .formatted(motivo, clienteId));
    }
}
