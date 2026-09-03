package com.conectsol.solarsync.projeto;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.conectsol.solarsync.pendencia.StatusPendencia;
import com.conectsol.solarsync.pendencia.event.PendenciaStatusChangedEvent;

import lombok.RequiredArgsConstructor;

/**
 * Implementa o requisito de integração entre etapas (CLAUDE.md seção 3): quando uma
 * Pendencia é marcada como RESOLVIDA, o Projeto correspondente deve ser criado/ativado
 * automaticamente, sem acoplar esse fluxo diretamente no módulo pendencia. Roda após o
 * commit da transação da Pendencia para evitar criar um Projeto órfão caso essa transação
 * seja revertida.
 */
@Component
@RequiredArgsConstructor
public class PendenciaResolvidaListener {

    private final ProjetoService projetoService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void aoResolverPendencia(PendenciaStatusChangedEvent evento) {
        if (evento.statusNovo() == StatusPendencia.RESOLVIDA) {
            projetoService.criarOuAtivarProjetoParaCliente(evento.clienteId(), evento.usuarioId());
        }
    }
}
