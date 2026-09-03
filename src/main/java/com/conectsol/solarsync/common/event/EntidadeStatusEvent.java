package com.conectsol.solarsync.common.event;

import java.time.Instant;

import com.conectsol.solarsync.common.EntidadeTipo;

/**
 * Marcador comum para qualquer evento de mudança de status de uma entidade do domínio.
 * Permite ao {@code HistoricoStatusEventListener} auditar qualquer módulo sem depender
 * de generics/type erasure, e serve de ponto de extensão único para futuros listeners
 * de integração (CRM, Gmail) sem acoplamento aos módulos que publicam os eventos.
 */
public interface EntidadeStatusEvent {

    EntidadeTipo getEntidadeTipo();

    Long getEntidadeId();

    String getStatusAnterior();

    String getStatusNovo();

    Instant getOcorridoEm();

    Long getUsuarioId();
}
