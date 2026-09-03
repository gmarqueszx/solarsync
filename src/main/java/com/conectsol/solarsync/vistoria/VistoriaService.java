package com.conectsol.solarsync.vistoria;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.common.exception.ProjetoSemInstalacaoException;
import com.conectsol.solarsync.common.exception.TransicaoStatusInvalidaException;
import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.conectsol.solarsync.vistoria.dto.VistoriaFiltro;
import com.conectsol.solarsync.vistoria.event.VistoriaStatusChangedEvent;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Etapa 4 do fluxo: vistoria da Coelba após a instalação. Único ponto autorizado a mudar o
 * status da vistoria, para a auditoria disparar sempre — é dela que saem as métricas de tempo
 * até a solicitação e de ciclo completo.
 */
@Service
@RequiredArgsConstructor
public class VistoriaService {

    private final VistoriaRepository vistoriaRepository;
    private final ProjetoRepository projetoRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<Vistoria> listar(VistoriaFiltro filtro, Pageable paginacao) {
        return vistoriaRepository.findAll(VistoriaSpecs.de(filtro), paginacao);
    }

    @Transactional(readOnly = true)
    public Vistoria buscar(Long id) {
        return carregar(id);
    }

    /**
     * Solicita a vistoria. Exige data de instalação registrada no projeto: vistoria é
     * pós-instalação, e sem esse marco a métrica de tempo até a solicitação não tem de onde
     * partir.
     */
    @Transactional
    public Vistoria solicitar(Long projetoId, LocalDate dataSolicitacao, Long usuarioId) {
        Projeto projeto = projetoRepository.findById(projetoId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Projeto não encontrado: " + projetoId));

        if (projeto.getDataInstalacao() == null) {
            throw new ProjetoSemInstalacaoException(projetoId);
        }

        Vistoria vistoria = vistoriaRepository.save(Vistoria.builder()
                .projeto(projeto)
                .dataSolicitacao(dataSolicitacao == null ? LocalDate.now() : dataSolicitacao)
                .status(StatusVistoria.SOLICITADA)
                .build());

        publicar(vistoria, null, StatusVistoria.SOLICITADA, usuarioId);
        return vistoria;
    }

    @Transactional
    public Vistoria aprovar(Long id, LocalDate dataResultado, Long usuarioId) {
        return registrarResultado(id, StatusVistoria.APROVADA, dataResultado, usuarioId);
    }

    @Transactional
    public Vistoria reprovar(Long id, LocalDate dataResultado, Long usuarioId) {
        return registrarResultado(id, StatusVistoria.REPROVADA, dataResultado, usuarioId);
    }

    /**
     * Nova solicitação depois de uma reprova, no mesmo registro — assim o histórico mostra
     * quantas idas e vindas aquele cliente teve, em vez de espalhar em vistorias soltas.
     */
    @Transactional
    public Vistoria resolicitar(Long id, LocalDate dataSolicitacao, Long usuarioId) {
        Vistoria vistoria = carregar(id);
        StatusVistoria statusAnterior = vistoria.getStatus();

        if (statusAnterior == StatusVistoria.SOLICITADA) {
            return vistoria;
        }
        if (!statusAnterior.podeIrPara(StatusVistoria.SOLICITADA)) {
            throw new TransicaoStatusInvalidaException(
                    "Vistoria", statusAnterior, StatusVistoria.SOLICITADA);
        }

        vistoria.setDataSolicitacao(dataSolicitacao == null ? LocalDate.now() : dataSolicitacao);
        vistoria.setDataResultado(null);
        vistoria.setStatus(StatusVistoria.SOLICITADA);
        Vistoria salva = vistoriaRepository.save(vistoria);

        publicar(salva, statusAnterior, StatusVistoria.SOLICITADA, usuarioId);
        return salva;
    }

    @Transactional
    public void excluir(Long id) {
        vistoriaRepository.delete(carregar(id));
    }

    private Vistoria registrarResultado(Long id, StatusVistoria novoStatus,
            LocalDate dataResultado, Long usuarioId) {

        Vistoria vistoria = carregar(id);
        StatusVistoria statusAnterior = vistoria.getStatus();

        // Idempotente, como nos outros módulos: repetir o resultado não republica evento.
        if (statusAnterior == novoStatus) {
            return vistoria;
        }
        if (!statusAnterior.podeIrPara(novoStatus)) {
            throw new TransicaoStatusInvalidaException("Vistoria", statusAnterior, novoStatus);
        }

        vistoria.setStatus(novoStatus);
        vistoria.setDataResultado(dataResultado == null ? LocalDate.now() : dataResultado);
        Vistoria salva = vistoriaRepository.save(vistoria);

        publicar(salva, statusAnterior, novoStatus, usuarioId);
        return salva;
    }

    private void publicar(Vistoria vistoria, StatusVistoria anterior, StatusVistoria novo,
            Long usuarioId) {
        eventPublisher.publishEvent(new VistoriaStatusChangedEvent(
                vistoria.getId(), vistoria.getProjeto().getId(), anterior, novo,
                Instant.now(), usuarioId));
    }

    private Vistoria carregar(Long id) {
        return vistoriaRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Vistoria não encontrada: " + id));
    }
}
