package com.conectsol.solarsync.pendencia.dto;

import java.time.Instant;

import com.conectsol.solarsync.auth.dto.UsuarioResumoResponse;
import com.conectsol.solarsync.cliente.dto.ClienteResumoResponse;
import com.conectsol.solarsync.pendencia.Pendencia;
import com.conectsol.solarsync.pendencia.StatusPendencia;
import com.conectsol.solarsync.pendencia.TipoPendencia;

/**
 * Os {@code @ManyToOne} de {@link Pendencia} não declaram {@code fetch}, logo são EAGER
 * (default do JPA) e este mapeamento é seguro mesmo com {@code spring.jpa.open-in-view=false}.
 * Se algum deles virar LAZY, este mapeamento precisa migrar para dentro do
 * {@code @Transactional} do service.
 */
public record PendenciaResponse(
        Long id,
        ClienteResumoResponse cliente,
        TipoPendencia tipo,
        StatusPendencia status,
        Instant solicitadoEm,
        Instant resolvidoEm,
        UsuarioResumoResponse responsavel,
        String observacao,
        Instant criadoEm,
        Instant atualizadoEm) {

    public static PendenciaResponse de(Pendencia pendencia) {
        return new PendenciaResponse(
                pendencia.getId(),
                ClienteResumoResponse.de(pendencia.getCliente()),
                pendencia.getTipo(),
                pendencia.getStatus(),
                pendencia.getSolicitadoEm(),
                pendencia.getResolvidoEm(),
                UsuarioResumoResponse.de(pendencia.getResponsavel()),
                pendencia.getObservacao(),
                pendencia.getCriadoEm(),
                pendencia.getAtualizadoEm());
    }
}
