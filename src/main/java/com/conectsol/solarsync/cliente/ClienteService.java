package com.conectsol.solarsync.cliente;

import java.time.Instant;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.cliente.dto.ClienteFiltro;
import com.conectsol.solarsync.cliente.dto.ClienteRequest;
import com.conectsol.solarsync.cliente.dto.ClienteResponse;
import com.conectsol.solarsync.cliente.dto.PrioridadeRequest;
import com.conectsol.solarsync.cliente.event.ClienteTriagemStatusChangedEvent;
import com.conectsol.solarsync.common.exception.PrioridadeSemInstalacaoException;
import com.conectsol.solarsync.common.web.PrioridadePrimeiro;
import com.conectsol.solarsync.projeto.ProjetoService;

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
    private final UsuarioRepository usuarioRepository;
    private final ProjetoService projetoService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<Cliente> listar(ClienteFiltro filtro, Pageable paginacao) {
        return clienteRepository.findAll(ClienteSpecs.de(filtro),
                PrioridadePrimeiro.aplicar(paginacao, "prioridade"));
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
                .somentePendencia(requisicao.somentePendencia())
                .banco(requisicao.banco())
                // Todo cliente entra na fila da triagem: é o requisito "todos os clientes
                // sejam checados se tem ou não pendência".
                .statusTriagem(StatusTriagem.AGUARDANDO_VERIFICACAO)
                .build();
        return ClienteResponse.de(clienteRepository.save(cliente));
    }

    /**
     * Cadastro vindo da sincronização com o Nectar (seção 9). Passa por aqui, e não por
     * {@code ClienteRepository.save} dentro do módulo de integração, para que todo caminho de
     * escrita de cliente continue num lugar só — é o que faz o cliente do CRM nascer na fila da
     * triagem igual ao digitado na tela, em vez de a integração poder inventar outro estado
     * inicial.
     * <p>
     * Devolve {@link java.util.Optional#empty()} quando o negócio já foi importado antes. É o
     * caso normal, não erro: o job relê a mesma página do CRM a cada execução.
     */
    @Transactional
    public Optional<ClienteResponse> criarDoNectar(ClienteRequest requisicao,
            String nectarOportunidadeId) {

        if (clienteRepository.existsByNectarOportunidadeId(nectarOportunidadeId)) {
            return Optional.empty();
        }

        Cliente cliente = Cliente.builder()
                .nome(requisicao.nome())
                .cidade(requisicao.cidade())
                .vendedor(requisicao.vendedor())
                .dataPagamento(requisicao.dataPagamento())
                .ucCoelba(requisicao.ucCoelba())
                .telefone(requisicao.telefone())
                .somentePendencia(requisicao.somentePendencia())
                // Ligado quando a oportunidade veio da etapa de entrada marcada como Banco no
                // Nectar. É o que dá ao cliente a etiqueta "Banco" ao lado do selo "CRM", e o
                // que manda o projeto aprovado para a etapa de Banco na volta ao CRM.
                .banco(requisicao.banco())
                .statusTriagem(StatusTriagem.AGUARDANDO_VERIFICACAO)
                .nectarOportunidadeId(nectarOportunidadeId)
                .build();
        return Optional.of(ClienteResponse.de(clienteRepository.save(cliente)));
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
        cliente.setSomentePendencia(requisicao.somentePendencia());
        cliente.setBanco(requisicao.banco());
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
     * Liga (ou revisa) a prioridade do cliente. Chamado quantas vezes for preciso: trocar o
     * motivo é o mesmo endpoint, e não um "desligar e ligar de novo" que apagaria de quem partiu
     * o pedido original.
     * <p>
     * A prioridade é do <b>cliente</b>, não da etapa em que ele está, e é isso que faz o
     * requisito funcionar sozinho: quando a etapa termina e o cliente avança, ele continua
     * prioritário e volta ao topo da fila seguinte sem ninguém remarcar nada.
     * <p>
     * Quando o motivo é a instalação adiantada, a data informada <b>desce para o projeto</b>
     * ({@code projeto.data_instalacao}) — não fica valendo por si. É o que fecha o requisito "a
     * data de instalação informada anteriormente deve estar disponível na etapa de vistoria": é
     * dali que a vistoria a lê, e é ela que destrava a solicitação. Se ainda não houver projeto
     * (o caso normal, já que a prioridade é marcada na triagem), a data espera no cliente e o
     * projeto a recebe ao nascer.
     */
    @Transactional
    public ClienteResponse marcarPrioridade(Long id, PrioridadeRequest requisicao, Long usuarioId) {
        // Validação cruzada entre dois campos: não cabe no Bean Validation do DTO sem uma
        // anotação de classe, e precisa valer também para origens que não passam pelo controller.
        if (requisicao.motivo().exigeDataDeInstalacao() && requisicao.dataInstalacao() == null) {
            throw new PrioridadeSemInstalacaoException(id, requisicao.motivo());
        }

        Cliente cliente = carregar(id);
        cliente.setPrioridade(true);
        cliente.setPrioridadeMotivo(requisicao.motivo());
        cliente.setPrioridadeObservacao(requisicao.observacao());
        cliente.setPrioridadeDefinidaEm(Instant.now());
        cliente.setPrioridadeDefinidaPor(resolverUsuario(usuarioId));
        // Motivo que não é de instalação não carrega data: deixar a anterior aqui faria o projeto
        // seguinte nascer "instalado" por causa de uma prioridade antiga que já mudou de razão.
        cliente.setPrioridadeDataInstalacao(
                requisicao.motivo().exigeDataDeInstalacao() ? requisicao.dataInstalacao() : null);

        Cliente salvo = clienteRepository.save(cliente);
        projetoService.registrarInstalacaoDeclaradaNaPrioridade(
                salvo.getId(), salvo.getPrioridadeDataInstalacao());
        return ClienteResponse.de(salvo);
    }

    /**
     * Encerra a prioridade: o cliente volta ao comportamento normal de ordenação.
     * <p>
     * A data de instalação já propagada para o projeto <b>fica</b>. Ela não é um privilégio, é um
     * fato de campo — a usina foi instalada naquele dia, e apagá-la porque a fila voltou ao normal
     * travaria a solicitação da vistoria mais adiante.
     */
    @Transactional
    public ClienteResponse removerPrioridade(Long id) {
        Cliente cliente = carregar(id);
        if (!cliente.isPrioridade()) {
            return ClienteResponse.de(cliente);
        }
        cliente.setPrioridade(false);
        cliente.setPrioridadeMotivo(null);
        cliente.setPrioridadeObservacao(null);
        cliente.setPrioridadeDefinidaEm(null);
        cliente.setPrioridadeDefinidaPor(null);
        cliente.setPrioridadeDataInstalacao(null);
        return ClienteResponse.de(clienteRepository.save(cliente));
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

    private Usuario resolverUsuario(Long usuarioId) {
        return usuarioId == null ? null : usuarioRepository.findById(usuarioId).orElse(null);
    }

    Cliente carregar(Long id) {
        return clienteRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Cliente não encontrado: " + id));
    }
}
