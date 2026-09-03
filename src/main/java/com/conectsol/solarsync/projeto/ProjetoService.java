package com.conectsol.solarsync.projeto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.projeto.event.ProjetoStatusChangedEvent;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Único ponto autorizado a criar/mudar o status de um {@link Projeto}. Qualquer origem
 * (tela, e-mail, webhook de CRM) deve passar por aqui para que a auditoria em
 * historico_status dispare de forma consistente.
 */
@Service
@RequiredArgsConstructor
public class ProjetoService {

    private static final List<StatusProjeto> STATUS_EM_ANDAMENTO =
            List.of(StatusProjeto.RECEBIDO, StatusProjeto.AGUARDANDO_ENVIO);

    private final ProjetoRepository projetoRepository;
    private final ClienteRepository clienteRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Reage à resolução de uma pendência: cria um novo Projeto com status RECEBIDO para o
     * cliente, a menos que já exista um projeto em andamento (RECEBIDO/AGUARDANDO_ENVIO)
     * para evitar duplicidade quando várias pendências do mesmo cliente são resolvidas.
     * O tipo do projeto começa como PADRAO e pode ser ajustado pelo analista responsável.
     */
    @Transactional
    public Projeto criarOuAtivarProjetoParaCliente(Long clienteId, Long usuarioId) {
        List<Projeto> projetosDoCliente = projetoRepository.findByClienteId(clienteId);
        boolean jaEmAndamento = projetosDoCliente.stream()
                .anyMatch(projeto -> STATUS_EM_ANDAMENTO.contains(projeto.getStatus()));
        if (jaEmAndamento) {
            return projetosDoCliente.stream()
                    .filter(projeto -> STATUS_EM_ANDAMENTO.contains(projeto.getStatus()))
                    .findFirst()
                    .orElseThrow();
        }

        Cliente cliente = clienteRepository.findById(clienteId)
                .orElseThrow(() -> new EntityNotFoundException("Cliente não encontrado: " + clienteId));

        Projeto projeto = Projeto.builder()
                .cliente(cliente)
                .tipoProjeto(TipoProjeto.PADRAO)
                .status(StatusProjeto.RECEBIDO)
                .dataRecebimento(LocalDate.now())
                .build();
        Projeto salvo = projetoRepository.save(projeto);

        eventPublisher.publishEvent(new ProjetoStatusChangedEvent(
                salvo.getId(), clienteId, null, StatusProjeto.RECEBIDO, Instant.now(), usuarioId));

        return salvo;
    }

    @Transactional
    public Projeto atualizarStatus(Long projetoId, StatusProjeto novoStatus, Long usuarioId) {
        Projeto projeto = projetoRepository.findById(projetoId)
                .orElseThrow(() -> new EntityNotFoundException("Projeto não encontrado: " + projetoId));

        StatusProjeto statusAnterior = projeto.getStatus();
        projeto.setStatus(novoStatus);
        if (novoStatus == StatusProjeto.APROVADO) {
            projeto.setDataAprovacao(LocalDate.now());
        }
        Projeto salvo = projetoRepository.save(projeto);

        eventPublisher.publishEvent(new ProjetoStatusChangedEvent(
                salvo.getId(),
                salvo.getCliente().getId(),
                statusAnterior,
                novoStatus,
                Instant.now(),
                usuarioId));

        return salvo;
    }
}
