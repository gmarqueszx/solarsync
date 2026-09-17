package com.conectsol.solarsync.projeto;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.conectsol.solarsync.cliente.StatusTriagem;
import com.conectsol.solarsync.cliente.event.ClienteTriagemStatusChangedEvent;

import lombok.RequiredArgsConstructor;

/**
 * O caminho "segue direto" da etapa 1: quando a triagem conclui que o cliente <b>não</b> tem
 * pendência na Coelba, o projeto dele nasce em RECEBIDO — exatamente o que
 * {@code PendenciaResolvidaListener} faz para o caminho com pendência. Sem isto, o cliente sem
 * pendência ficava fora do fluxo automático e alguém tinha que lembrar de criar o projeto na
 * mão.
 * <p>
 * O projeto nasce mesmo que o cliente deva: débito bloqueia o <b>envio</b> à Coelba, não a
 * existência do projeto — é assim que o gestor vê o cliente travado em vez de ele desaparecer
 * das telas (decisão registrada no CLAUDE.md seção 6).
 */
@Component
@RequiredArgsConstructor
public class TriagemSemPendenciaListener {

    private final ProjetoService projetoService;

    /**
     * {@code REQUIRES_NEW} é obrigatório: em {@code AFTER_COMMIT} a transação original ainda
     * está ligada à thread, então um {@code @Transactional} comum entraria nela — já
     * commitada — e o insert do Projeto seria descartado em silêncio, sem exceção. Mesma
     * armadilha documentada em {@code PendenciaResolvidaListener}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void aoConcluirSemPendencia(ClienteTriagemStatusChangedEvent evento) {
        if (evento.statusNovo() == StatusTriagem.SEM_PENDENCIA) {
            projetoService.criarOuAtivarProjetoParaCliente(evento.clienteId(), evento.usuarioId());
        }
    }
}
