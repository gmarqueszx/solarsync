package com.conectsol.solarsync.debito.event;

import java.time.Instant;

import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.common.event.EntidadeStatusEvent;
import com.conectsol.solarsync.debito.StatusDebito;

/**
 * Publicado a cada mudança de situação do débito. É o que permite ao dashboard calcular
 * "tempo médio parado por débito" (seção 5 do CLAUDE.md): a diferença entre a linha
 * {@code null → ATIVO} e a {@code ATIVO → QUITADO} em {@code historico_status}. Sem o evento,
 * essa métrica exigiria colunas de data extra na tabela.
 */
public record DebitoStatusChangedEvent(
        Long debitoId,
        Long clienteId,
        StatusDebito statusAnterior,
        StatusDebito statusNovo,
        Instant ocorridoEm,
        Long usuarioId) implements EntidadeStatusEvent {

    @Override
    public EntidadeTipo getEntidadeTipo() {
        return EntidadeTipo.DEBITO;
    }

    @Override
    public Long getEntidadeId() {
        return debitoId;
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
