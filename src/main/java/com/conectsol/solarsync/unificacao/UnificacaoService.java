package com.conectsol.solarsync.unificacao;

import java.time.Instant;
import java.time.LocalDate;

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
import com.conectsol.solarsync.common.exception.UnificacaoNaoFeitaException;
import com.conectsol.solarsync.unificacao.dto.UnificacaoFiltro;
import com.conectsol.solarsync.unificacao.dto.UnificacaoRequest;
import com.conectsol.solarsync.unificacao.event.UnificacaoStatusChangedEvent;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Etapa 4 do fluxo: unificação de unidades consumidoras e desligamento do medidor antigo.
 * <p>
 * {@code feita} continua sendo um marco simples — unificou ou não. Já o desligamento do medidor
 * tem ciclo próprio ({@link StatusDesligamento}): solicita-se e aguarda-se o retorno, com O.S.
 * como desvio quando a equipe de campo não realiza. Por isso ele tem máquina de estados e
 * publica evento, ao contrário do que este módulo fazia antes — a modelagem mudou quando o
 * processo real ficou claro, e um booleano não representava "solicitado, aguardando".
 */
@Service
@RequiredArgsConstructor
public class UnificacaoService {

    private final UnificacaoRepository unificacaoRepository;
    private final ClienteRepository clienteRepository;
    private final UsuarioRepository usuarioRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<Unificacao> listar(UnificacaoFiltro filtro, Pageable paginacao) {
        return unificacaoRepository.findAll(UnificacaoSpecs.de(filtro), paginacao);
    }

    @Transactional(readOnly = true)
    public Unificacao buscar(Long id) {
        return carregar(id);
    }

    @Transactional
    public Unificacao criar(UnificacaoRequest requisicao) {
        Cliente cliente = clienteRepository.findById(requisicao.clienteId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Cliente não encontrado: " + requisicao.clienteId()));

        return unificacaoRepository.save(Unificacao.builder()
                .cliente(cliente)
                .cidade(requisicao.cidade() == null ? cliente.getCidade() : requisicao.cidade())
                .projetista(resolverProjetista(requisicao.projetistaId()))
                .informacoes(requisicao.informacoes())
                .feita(false)
                .desligamentoStatus(StatusDesligamento.NAO_SOLICITADO)
                .build());
    }

    @Transactional
    public Unificacao atualizar(Long id, UnificacaoRequest requisicao) {
        Unificacao unificacao = carregar(id);
        unificacao.setCidade(requisicao.cidade());
        unificacao.setProjetista(resolverProjetista(requisicao.projetistaId()));
        unificacao.setInformacoes(requisicao.informacoes());
        return unificacaoRepository.save(unificacao);
    }

    @Transactional
    public Unificacao marcarFeita(Long id, boolean feita) {
        Unificacao unificacao = carregar(id);
        unificacao.setFeita(feita);
        return unificacaoRepository.save(unificacao);
    }

    /**
     * Pede o desligamento do medidor unificado e passa a aguardar retorno. Exige a unificação
     * confirmada: pedir antes desligaria um medidor de que o cliente ainda depende.
     */
    @Transactional
    public Unificacao solicitarDesligamento(Long id, LocalDate dataSolicitacao, Long usuarioId) {
        Unificacao unificacao = carregar(id);
        if (!unificacao.isFeita()) {
            throw new UnificacaoNaoFeitaException(id);
        }
        unificacao.setDesligamentoSolicitadoEm(
                dataSolicitacao == null ? LocalDate.now() : dataSolicitacao);
        return transicionar(unificacao, StatusDesligamento.SOLICITADO, usuarioId);
    }

    /** A equipe de campo não realizou o desligamento; abriu-se ordem de serviço. */
    @Transactional
    public Unificacao abrirOrdemDeServico(Long id, Long usuarioId) {
        return transicionar(carregar(id), StatusDesligamento.OS_ABERTA, usuarioId);
    }

    @Transactional
    public Unificacao concluirDesligamento(Long id, LocalDate dataConclusao, Long usuarioId) {
        Unificacao unificacao = carregar(id);
        unificacao.setDesligamentoConcluidoEm(
                dataConclusao == null ? LocalDate.now() : dataConclusao);
        return transicionar(unificacao, StatusDesligamento.CONCLUIDO, usuarioId);
    }

    private Unificacao transicionar(Unificacao unificacao, StatusDesligamento novoStatus,
            Long usuarioId) {

        StatusDesligamento statusAnterior = unificacao.getDesligamentoStatus();

        // Idempotente, como nos outros módulos: repetir o status não republica evento.
        if (statusAnterior == novoStatus) {
            return unificacaoRepository.save(unificacao);
        }
        if (!statusAnterior.podeIrPara(novoStatus)) {
            throw new TransicaoStatusInvalidaException("Desligamento", statusAnterior, novoStatus);
        }

        unificacao.setDesligamentoStatus(novoStatus);
        Unificacao salva = unificacaoRepository.save(unificacao);

        eventPublisher.publishEvent(new UnificacaoStatusChangedEvent(
                salva.getId(), salva.getCliente().getId(), statusAnterior, novoStatus,
                Instant.now(), usuarioId));

        return salva;
    }

    @Transactional
    public void excluir(Long id) {
        unificacaoRepository.delete(carregar(id));
    }

    private Usuario resolverProjetista(Long projetistaId) {
        if (projetistaId == null) {
            return null;
        }
        return usuarioRepository.findById(projetistaId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Usuário não encontrado: " + projetistaId));
    }

    private Unificacao carregar(Long id) {
        return unificacaoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Unificação não encontrada: " + id));
    }
}
