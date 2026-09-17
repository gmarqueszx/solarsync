package com.conectsol.solarsync.pendencia;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.common.exception.ClienteComDebitoException;
import com.conectsol.solarsync.common.exception.DebitoNaoConsultadoException;
import com.conectsol.solarsync.common.exception.TransicaoStatusInvalidaException;
import com.conectsol.solarsync.debito.DebitoService;
import com.conectsol.solarsync.debito.TipoDebito;
import com.conectsol.solarsync.pendencia.dto.PendenciaAtualizarRequest;
import com.conectsol.solarsync.pendencia.dto.PendenciaCriarRequest;
import com.conectsol.solarsync.pendencia.dto.PendenciaFiltro;
import com.conectsol.solarsync.pendencia.event.PendenciaStatusChangedEvent;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Único ponto autorizado a mudar o status de uma {@link Pendencia}. Qualquer origem da
 * mudança (tela, e-mail, webhook de CRM) deve passar por aqui, para que a automação de
 * avanço de etapa e a auditoria em historico_status disparem de forma consistente.
 */
@Service
@RequiredArgsConstructor
public class PendenciaService {

    private final PendenciaRepository pendenciaRepository;
    private final ClienteRepository clienteRepository;
    private final UsuarioRepository usuarioRepository;
    private final DebitoService debitoService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<Pendencia> listar(PendenciaFiltro filtro, Pageable paginacao) {
        return pendenciaRepository.findAll(PendenciaSpecs.de(filtro), paginacao);
    }

    @Transactional(readOnly = true)
    public Pendencia buscar(Long id) {
        return carregar(id);
    }

    @Transactional
    public Pendencia criar(PendenciaCriarRequest requisicao, Long usuarioId) {
        Cliente cliente = clienteRepository.findById(requisicao.clienteId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Cliente não encontrado: " + requisicao.clienteId()));

        Pendencia pendencia = Pendencia.builder()
                .cliente(cliente)
                .tipo(requisicao.tipo())
                .status(StatusPendencia.ABERTA)
                .solicitadoEm(requisicao.solicitadoEm() == null
                        ? Instant.now()
                        : requisicao.solicitadoEm())
                .responsavel(resolverResponsavel(requisicao.responsavelId()))
                .observacao(requisicao.observacao())
                .build();

        Pendencia salva = pendenciaRepository.save(pendencia);

        // Auditar a criação (null -> ABERTA) é o que permite ao dashboard medir "tempo médio
        // sem ninguém mexer no cliente" a partir de um marco confiável.
        eventPublisher.publishEvent(new PendenciaStatusChangedEvent(
                salva.getId(), cliente.getId(), null, StatusPendencia.ABERTA,
                Instant.now(), usuarioId));

        return salva;
    }

    @Transactional
    public Pendencia atualizar(Long id, PendenciaAtualizarRequest requisicao) {
        Pendencia pendencia = carregar(id);
        pendencia.setTipo(requisicao.tipo());
        pendencia.setResponsavel(resolverResponsavel(requisicao.responsavelId()));
        pendencia.setObservacao(requisicao.observacao());
        return pendenciaRepository.save(pendencia);
    }

    @Transactional
    public void excluir(Long id) {
        pendenciaRepository.delete(carregar(id));
    }

    /** Mantida para o {@code PendenciaResolvidaListener} e para chamadas sem observação. */
    @Transactional
    public Pendencia atualizarStatus(Long pendenciaId, StatusPendencia novoStatus, Long usuarioId) {
        return atualizarStatus(pendenciaId, novoStatus, usuarioId, null);
    }

    @Transactional
    public Pendencia atualizarStatus(Long pendenciaId, StatusPendencia novoStatus, Long usuarioId,
            String observacao) {

        Pendencia pendencia = carregar(pendenciaId);
        StatusPendencia statusAnterior = pendencia.getStatus();

        if (observacao != null && !observacao.isBlank()) {
            pendencia.setObservacao(observacao);
        }

        // Idempotente: repetir o status atual não republica evento, para um duplo clique não
        // gerar duas linhas de histórico e distorcer as métricas.
        if (statusAnterior == novoStatus) {
            return pendenciaRepository.save(pendencia);
        }

        if (!statusAnterior.podeIrPara(novoStatus)) {
            throw new TransicaoStatusInvalidaException("Pendência", statusAnterior, novoStatus);
        }

        if (novoStatus == StatusPendencia.RESOLVIDA) {
            exigirClienteSemDebito(pendencia.getCliente().getId());
        }

        pendencia.setStatus(novoStatus);
        pendencia.setResolvidoEm(
                novoStatus == StatusPendencia.RESOLVIDA ? Instant.now() : null);
        Pendencia salva = pendenciaRepository.save(pendencia);

        eventPublisher.publishEvent(new PendenciaStatusChangedEvent(
                salva.getId(),
                salva.getCliente().getId(),
                statusAnterior,
                novoStatus,
                Instant.now(),
                usuarioId));

        return salva;
    }

    /**
     * Etapa 1 do fluxo: só se resolve pendência de cliente sem débito — com débito, a pendência
     * fica travada até a quitação. Guarda no service, e não no controller, para valer também
     * quando a origem for a leitura de e-mail ou um webhook do CRM.
     * <p>
     * Olha <b>só</b> o débito do tipo {@code PENDENCIA}: é o que impede a Coelba de executar a
     * troca de titularidade, a ligação nova e afins. Débito que trava a homologação não tem
     * nada a ver com esta etapa, e barrar por ele deixaria a pendência parada por um motivo
     * que quem a trabalha não consegue resolver.
     * <p>
     * São <b>duas</b> recusas diferentes de propósito, e o {@code codigo} do erro distingue
     * qual: {@code DEBITO_NAO_CONSULTADO} pede a consulta na agência virtual (ninguém sabe se
     * o cliente deve), {@code CLIENTE_COM_DEBITO} pede a cobrança (sabe-se que deve). Juntar
     * as duas numa mensagem só mandaria a analista cobrar um cliente que talvez não deva nada.
     * <p>
     * A pendência <b>não</b> muda de status quando está travada. "Travada por débito" é fato
     * derivado (pendência aberta + débito ativo), exposto pelo filtro
     * {@code ?travadaPorDebito=true} — um status próprio precisaria ser desfeito por outro
     * evento quando o cliente quitasse, e viveria dessincronizado de {@code debito.status}.
     */
    private void exigirClienteSemDebito(Long clienteId) {
        if (!debitoService.clienteTemConsultaRegistrada(clienteId, TipoDebito.PENDENCIA)) {
            throw new DebitoNaoConsultadoException(clienteId, TipoDebito.PENDENCIA);
        }
        if (debitoService.clienteTemDebitoAtivo(clienteId, TipoDebito.PENDENCIA)) {
            throw new ClienteComDebitoException(clienteId, "resolver a pendência");
        }
    }

    private Usuario resolverResponsavel(Long responsavelId) {
        if (responsavelId == null) {
            return null;
        }
        return usuarioRepository.findById(responsavelId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Usuário não encontrado: " + responsavelId));
    }

    private Pendencia carregar(Long id) {
        return pendenciaRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Pendencia não encontrada: " + id));
    }
}
