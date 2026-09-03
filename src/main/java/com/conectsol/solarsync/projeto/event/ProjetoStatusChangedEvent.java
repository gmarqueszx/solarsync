package com.conectsol.solarsync.projeto.event;

import java.time.Instant;

import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.common.event.EntidadeStatusEvent;
import com.conectsol.solarsync.projeto.StatusProjeto;

public record ProjetoStatusChangedEvent(
        Long projetoId,
        Long clienteId,
        StatusProjeto statusAnterior,
        StatusProjeto statusNovo,
        Instant ocorridoEm,
        Long usuarioId) implements EntidadeStatusEvent {

    @Override
    public EntidadeTipo getEntidadeTipo() {
        return EntidadeTipo.PROJETO;
    }

    @Override
    public Long getEntidadeId() {
        return projetoId;
    }

    @Override
    public String getStatusAnterior() {
        return statusAnterior == null ? null : statusAnterior.name();
    }

    @Override
    public String getStatusNovo() {
        return statusNovo.name();
    }

    @Override
    public Instant getOcorridoEm() {
        return ocorridoEm;
    }

    @Override
    public Long getUsuarioId() {
        return usuarioId;
    }
}
