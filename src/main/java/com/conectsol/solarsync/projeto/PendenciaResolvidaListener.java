package com.conectsol.solarsync.projeto;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    /**
     * {@code REQUIRES_NEW} é obrigatório aqui: em {@code AFTER_COMMIT} a transação original
     * ainda está ligada à thread, então um {@code @Transactional} comum (REQUIRED) entraria
     * nela — já commitada — e o insert do Projeto seria descartado em silêncio, sem exceção.
     * <p>
     * Consequência aceita: a criação do Projeto é uma transação separada da atualização da
     * Pendencia. Se ela falhar, a Pendencia continua RESOLVIDA e o fluxo fica pela metade —
     * o preço de não criar projeto órfão. Se isso virar problema real, a solução é outbox
     * com reprocessamento, não voltar para o mesmo commit.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void aoResolverPendencia(PendenciaStatusChangedEvent evento) {
        if (evento.statusNovo() == StatusPendencia.RESOLVIDA) {
            projetoService.criarOuAtivarProjetoParaCliente(evento.clienteId(), evento.usuarioId());
        }
    }
}
