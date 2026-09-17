package com.conectsol.solarsync.cliente;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.pendencia.event.PendenciaStatusChangedEvent;

import lombok.RequiredArgsConstructor;

/**
 * Criar uma pendência é, por si, a prova de que o cliente foi checado na Coelba — então a
 * triagem dele passa a COM_PENDENCIA sem ninguém marcar nada. Manter isso automático é o que
 * evita reintroduzir o retrabalho de atualizar a situação em dois lugares, que é a dor da
 * planilha + Trello (CLAUDE.md seção 1).
 */
@Component
@RequiredArgsConstructor
public class PendenciaCriadaTriagemListener {

    private final ClienteService clienteService;

    /**
     * Síncrono e na <b>mesma</b> transação da pendência, ao contrário de
     * {@code TriagemSemPendenciaListener}: aqui não há registro novo para ficar órfão — é um
     * update no cliente que já está na transação —, e é justamente o que se quer que a
     * pendência e a triagem do cliente nunca divirjam. Por não ser {@code AFTER_COMMIT}, não se
     * aplica a armadilha do {@code REQUIRES_NEW} descrita no CLAUDE.md.
     * <p>
     * {@code statusAnterior == null} é a assinatura da criação: {@code PendenciaService.criar}
     * publica {@code null -> ABERTA}. As transições seguintes não mexem na triagem.
     */
    @EventListener
    @Transactional
    public void aoCriarPendencia(PendenciaStatusChangedEvent evento) {
        if (evento.statusAnterior() == null) {
            clienteService.marcarComPendencia(evento.clienteId(), evento.usuarioId());
        }
    }
}
