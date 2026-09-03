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
import com.conectsol.solarsync.common.exception.TransicaoStatusInvalidaException;
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
