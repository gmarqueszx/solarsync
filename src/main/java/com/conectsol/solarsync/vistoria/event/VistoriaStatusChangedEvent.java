package com.conectsol.solarsync.vistoria.event;

import java.time.Instant;

import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.common.event.EntidadeStatusEvent;
import com.conectsol.solarsync.vistoria.StatusVistoria;

public record VistoriaStatusChangedEvent(
        Long vistoriaId,
        Long projetoId,
        StatusVistoria statusAnterior,
        StatusVistoria statusNovo,
        Instant ocorridoEm,
        Long usuarioId) implements EntidadeStatusEvent {

    @Override
    public EntidadeTipo getEntidadeTipo() {
        return EntidadeTipo.VISTORIA;
    }

    @Override
    public Long getEntidadeId() {
        return vistoriaId;
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
