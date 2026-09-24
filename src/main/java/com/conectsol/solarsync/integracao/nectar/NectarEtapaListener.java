package com.conectsol.solarsync.integracao.nectar;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.conectsol.solarsync.pendencia.event.PendenciaStatusChangedEvent;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.conectsol.solarsync.projeto.event.ProjetoStatusChangedEvent;
import com.conectsol.solarsync.vistoria.event.VistoriaStatusChangedEvent;

import lombok.RequiredArgsConstructor;

/**
 * Liga as transições do domínio à etapa do cliente no Nectar. É o ponto de extensão que a seção 3
 * do CLAUDE.md prometia: nenhum dos três módulos de etapa sabe que esta classe existe, e nenhum
 * precisou mudar para a sincronização passar a acontecer — o que também significa que uma origem
 * nova de mudança de status (a leitura do e-mail da Coelba, por exemplo) já cai aqui de graça.
 * <p>
 * <b>{@code AFTER_COMMIT}</b>: o status no SolarSync é gravado primeiro e não depende do CRM. Se a
 * transação do domínio for revertida, nada é enviado; se o envio falhar, o status permanece. As
 * duas coisas na ordem certa.
 * <p>
 * Três métodos em vez de um sobre {@code EntidadeStatusEvent}, apesar de a interface comum
 * existir: cada evento traz um caminho diferente até o cliente (a pendência e o projeto trazem o
 * {@code clienteId}; a vistoria traz o projeto) e a vistoria ainda precisa do status <b>anterior</b>
 * para distinguir a primeira solicitação da que vem depois de uma reprova. Um método genérico
 * seria três {@code instanceof} com o mesmo código dentro.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = { "solarsync.nectar.ativo", "solarsync.nectar.saida.ativo" },
        havingValue = "true")
public class NectarEtapaListener {

    private final NectarEtapaService etapaService;
    private final ProjetoRepository projetoRepository;

    /**
     * Pendência aberta (ou reaberta) põe o cliente na etapa de pendência do CRM. A resolução não
     * tem etapa própria de propósito — ver {@link EtapaDoFluxo#de(com.conectsol.solarsync.pendencia.StatusPendencia)}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void aoMudarPendencia(PendenciaStatusChangedEvent evento) {
        etapaService.sincronizar(evento.clienteId(), EtapaDoFluxo.de(evento.statusNovo()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void aoMudarProjeto(ProjetoStatusChangedEvent evento) {
        etapaService.sincronizar(evento.clienteId(), EtapaDoFluxo.de(evento.statusNovo()));
    }

    /**
     * A vistoria não carrega o cliente, só o projeto — e a consulta é feita aqui, fora da
     * transação do domínio, porque é a integração que precisa dela. Acrescentar {@code clienteId}
     * ao evento resolveria em um lugar e acrescentaria um campo que só este código usa em outro.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void aoMudarVistoria(VistoriaStatusChangedEvent evento) {
        projetoRepository.findById(evento.projetoId()).ifPresent(projeto ->
                etapaService.sincronizar(
                        projeto.getCliente().getId(),
                        EtapaDoFluxo.de(evento.statusAnterior(), evento.statusNovo())));
    }
}
