package com.conectsol.solarsync.cliente;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.cliente.dto.ClienteFiltro;
import com.conectsol.solarsync.cliente.dto.ClienteRequest;
import com.conectsol.solarsync.cliente.dto.ClienteResponse;
import com.conectsol.solarsync.cliente.event.ClienteTriagemStatusChangedEvent;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Cadastro do cliente e, com ele, a triagem de pendência da etapa 1 — o único ponto autorizado
 * a mudar {@code statusTriagem}, para que a auditoria e a criação automática do projeto
 * disparem igual venha a mudança da tela, do CRM ou de um e-mail.
 */
@Service
@RequiredArgsConstructor
public class ClienteService {

    private final ClienteRepository clienteRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<Cliente> listar(ClienteFiltro filtro, Pageable paginacao) {
        return clienteRepository.findAll(ClienteSpecs.de(filtro), paginacao);
    }

    @Transactional(readOnly = true)
    public ClienteResponse buscar(Long id) {
        return ClienteResponse.de(carregar(id));
    }

    @Transactional(readOnly = true)
    public long quantidadeAguardandoVerificacao() {
        return clienteRepository.countByStatusTriagem(StatusTriagem.AGUARDANDO_VERIFICACAO);
    }

    @Transactional
    public ClienteResponse criar(ClienteRequest requisicao) {
        Cliente cliente = Cliente.builder()
                .nome(requisicao.nome())
                .cidade(requisicao.cidade())
                .vendedor(requisicao.vendedor())
                .dataPagamento(requisicao.dataPagamento())
                .ucCoelba(requisicao.ucCoelba())
                .telefone(requisicao.telefone())
                // Todo cliente entra na fila da triagem: é o requisito "todos os clientes
                // sejam checados se tem ou não pendência".
                .statusTriagem(StatusTriagem.AGUARDANDO_VERIFICACAO)
                .build();
        return ClienteResponse.de(clienteRepository.save(cliente));
    }

    /**
     * O PUT substitui o cadastro inteiro, mas <b>não</b> mexe em {@code statusTriagem}: status
     * só muda por endpoint de ação, como no resto da API. Corrigir o telefone do cliente não
     * pode, de passagem, apagar o fato de que a Coelba já foi consultada.
     */
    @Transactional
    public ClienteResponse atualizar(Long id, ClienteRequest requisicao) {
        Cliente cliente = carregar(id);
        cliente.setNome(requisicao.nome());
        cliente.setCidade(requisicao.cidade());
        cliente.setVendedor(requisicao.vendedor());
        cliente.setDataPagamento(requisicao.dataPagamento());
        cliente.setUcCoelba(requisicao.ucCoelba());
        cliente.setTelefone(requisicao.telefone());
        return ClienteResponse.de(clienteRepository.save(cliente));
    }

    @Transactional
    public void excluir(Long id) {
        clienteRepository.delete(carregar(id));
    }

    /**
     * A analista checou a Coelba e não há nada pendente. Publica o evento que faz o projeto
     * nascer em RECEBIDO e o cliente cair na fila de consulta de débito.
     */
    @Transactional
    public ClienteResponse marcarSemPendencia(Long id, Long usuarioId) {
        return atualizarStatusTriagem(id, StatusTriagem.SEM_PENDENCIA, usuarioId);
    }

    /**
     * Chamado pelo listener quando uma Pendencia é criada — o cliente foi checado e tem
     * pendência. Deixar isso a cargo de quem cria a pendência é o que evita o retrabalho de
     * atualizar a situação em dois lugares, que é a dor da planilha + Trello (seção 1).
     */
    @Transactional
    public void marcarComPendencia(Long clienteId, Long usuarioId) {
        atualizarStatusTriagem(clienteId, StatusTriagem.COM_PENDENCIA, usuarioId);
    }

    /**
     * Devolve o cliente para a fila de verificação. Serve para o novo ciclo (uma ampliação
     * meses depois pede recheca) e para desfazer marcação errada.
     */
    @Transactional
    public ClienteResponse reverificar(Long id, Long usuarioId) {
        return atualizarStatusTriagem(id, StatusTriagem.AGUARDANDO_VERIFICACAO, usuarioId);
    }

    private ClienteResponse atualizarStatusTriagem(Long id, StatusTriagem novoStatus,
            Long usuarioId) {

        Cliente cliente = carregar(id);
        StatusTriagem statusAnterior = cliente.getStatusTriagem();

        // Idempotente, como no resto da API: repetir o status atual não republica evento, para
        // um duplo clique não gerar duas linhas de histórico nem um segundo projeto.
        if (statusAnterior == novoStatus) {
            return ClienteResponse.de(cliente);
        }

        cliente.setStatusTriagem(novoStatus);
        Cliente salvo = clienteRepository.save(cliente);

        eventPublisher.publishEvent(new ClienteTriagemStatusChangedEvent(
                salvo.getId(), statusAnterior, novoStatus, Instant.now(), usuarioId));

        return ClienteResponse.de(salvo);
    }

    Cliente carregar(Long id) {
        return clienteRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Cliente não encontrado: " + id));
    }
}
