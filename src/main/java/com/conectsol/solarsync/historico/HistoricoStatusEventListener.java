package com.conectsol.solarsync.historico;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.conectsol.solarsync.common.event.EntidadeStatusEvent;

import lombok.RequiredArgsConstructor;

/**
 * Audita qualquer mudança de status de qualquer módulo, na mesma transação da mudança
 * (evento síncrono) — garante que nunca existe transição de status sem registro em
 * historico_status.
 */
@Component
@RequiredArgsConstructor
public class HistoricoStatusEventListener {

    private final HistoricoStatusService historicoStatusService;

    @EventListener
    public void aoMudarStatus(EntidadeStatusEvent evento) {
        historicoStatusService.registrar(evento);
    }
}
