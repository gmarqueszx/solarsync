package com.conectsol.solarsync.pendencia.dto;

import java.time.Instant;

import com.conectsol.solarsync.pendencia.Pendencia;
import com.conectsol.solarsync.pendencia.StatusPendencia;
import com.conectsol.solarsync.pendencia.TipoPendencia;

/**
 * Projeção de listagem: a tabela do frontend não precisa de {@code observacao} (até 1000
 * caracteres) nem do cliente completo.
 */
public record PendenciaResumoResponse(
        Long id,
        Long clienteId,
        String clienteNome,
        TipoPendencia tipo,
        StatusPendencia status,
        Instant solicitadoEm,
        Instant resolvidoEm,
        Long responsavelId,
        String responsavelNome) {

    public static PendenciaResumoResponse de(Pendencia pendencia) {
        return new PendenciaResumoResponse(
                pendencia.getId(),
                pendencia.getCliente().getId(),
                pendencia.getCliente().getNome(),
                pendencia.getTipo(),
                pendencia.getStatus(),
                pendencia.getSolicitadoEm(),
                pendencia.getResolvidoEm(),
                pendencia.getResponsavel() == null ? null : pendencia.getResponsavel().getId(),
                pendencia.getResponsavel() == null ? null : pendencia.getResponsavel().getNome());
    }
}
