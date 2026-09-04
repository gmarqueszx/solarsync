package com.conectsol.solarsync.unificacao.event;

import java.time.Instant;

import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.common.event.EntidadeStatusEvent;
import com.conectsol.solarsync.unificacao.StatusDesligamento;

/**
 * Mudanças no desligamento do medidor unificado.
 * <p>
 * Este evento não existia: a Unificação era o único módulo sem máquina de estados nem auditoria,
 * porque só tinha dois booleanos independentes. Quando o desligamento passou a ter ciclo de
 * solicitar e aguardar retorno, passou também a fazer sentido medir o tempo dessa espera — e é
 * daqui que esse número sai.
 */
public record UnificacaoStatusChangedEvent(
        Long unificacaoId,
        Long clienteId,
        StatusDesligamento statusAnterior,
        StatusDesligamento statusNovo,
        Instant ocorridoEm,
        Long usuarioId) implements EntidadeStatusEvent {

    @Override
    public EntidadeTipo getEntidadeTipo() {
        return EntidadeTipo.UNIFICACAO;
    }

    @Override
    public Long getEntidadeId() {
        return unificacaoId;
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
