package com.conectsol.solarsync.cliente.event;

import java.time.Instant;

import com.conectsol.solarsync.cliente.StatusTriagem;
import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.common.event.EntidadeStatusEvent;

/**
 * A triagem de pendência do cliente mudou. É o gatilho de {@code TriagemSemPendenciaListener},
 * que cria o projeto quando a checagem dá "sem pendência" — o mesmo papel que
 * {@code PendenciaStatusChangedEvent} tem para a pendência resolvida.
 * <p>
 * A criação do cliente <b>não</b> publica este evento: o marco "entrou na fila de verificação"
 * já é o {@code cliente.criado_em}, e uma linha {@code null -> AGUARDANDO_VERIFICACAO} em
 * historico_status não acrescentaria informação nenhuma.
 */
public record ClienteTriagemStatusChangedEvent(
        Long clienteId,
        StatusTriagem statusAnterior,
        StatusTriagem statusNovo,
        Instant ocorridoEm,
        Long usuarioId) implements EntidadeStatusEvent {

    @Override
    public EntidadeTipo getEntidadeTipo() {
        return EntidadeTipo.CLIENTE;
    }

    @Override
    public Long getEntidadeId() {
        return clienteId;
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
