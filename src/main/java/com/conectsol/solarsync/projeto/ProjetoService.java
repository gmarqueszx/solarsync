package com.conectsol.solarsync.projeto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
import com.conectsol.solarsync.common.exception.TransicaoStatusInvalidaException;
import com.conectsol.solarsync.debito.DebitoService;
import com.conectsol.solarsync.projeto.dto.ProjetoAtualizarRequest;
import com.conectsol.solarsync.projeto.dto.ProjetoCriarRequest;
import com.conectsol.solarsync.projeto.dto.ProjetoFiltro;
import com.conectsol.solarsync.projeto.event.ProjetoStatusChangedEvent;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Único ponto autorizado a criar/mudar o status de um {@link Projeto}. Qualquer origem
 * (tela, e-mail, webhook de CRM) deve passar por aqui para que a auditoria em
 * historico_status dispare de forma consistente.
 * <p>
 * Cada ação do fluxo tem seu próprio método, em vez de um {@code atualizarStatus} com um saco
 * de parâmetros opcionais: assim cada transição preenche exatamente as datas que lhe dizem
 * respeito. Preencher {@code dataEncaminhado} importa porque a métrica
 * {@code data_encaminhado - data_recebimento} do dashboard depende dela.
 */
@Service
@RequiredArgsConstructor
public class ProjetoService {

    private static final List<StatusProjeto> STATUS_EM_ANDAMENTO =
            List.of(StatusProjeto.RECEBIDO, StatusProjeto.AGUARDANDO_ENVIO);

    private final ProjetoRepository projetoRepository;
    private final ClienteRepository clienteRepository;
    private final UsuarioRepository usuarioRepository;
    private final DebitoService debitoService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<Projeto> listar(ProjetoFiltro filtro, Pageable paginacao) {
        return projetoRepository.findAll(ProjetoSpecs.de(filtro), paginacao);
    }

    @Transactional(readOnly = true)
    public Projeto buscar(Long id) {
        return carregar(id);
    }

    /**
     * Reage à resolução de uma pendência: cria um novo Projeto com status RECEBIDO para o
     * cliente, a menos que já exista um projeto em andamento (RECEBIDO/AGUARDANDO_ENVIO)
     * para evitar duplicidade quando várias pendências do mesmo cliente são resolvidas.
     * O tipo do projeto começa como PADRAO e pode ser ajustado pelo analista responsável.
     */
    @Transactional
    public Projeto criarOuAtivarProjetoParaCliente(Long clienteId, Long usuarioId) {
        List<Projeto> projetosDoCliente = projetoRepository.findByClienteId(clienteId);
        return projetosDoCliente.stream()
                .filter(projeto -> STATUS_EM_ANDAMENTO.contains(projeto.getStatus()))
                .findFirst()
                .orElseGet(() -> nascerRecebido(
                        carregarCliente(clienteId), TipoProjeto.PADRAO, null,
                        LocalDate.now(), null, usuarioId));
    }

    @Transactional
    public Projeto criar(ProjetoCriarRequest requisicao, Long usuarioId) {
        return nascerRecebido(
                carregarCliente(requisicao.clienteId()),
                requisicao.tipoProjeto(),
                resolverAnalista(requisicao.analistaResponsavelId()),
                requisicao.dataRecebimento() == null ? LocalDate.now() : requisicao.dataRecebimento(),
                requisicao.dataArt(),
                usuarioId);
    }

    @Transactional
    public Projeto atualizar(Long id, ProjetoAtualizarRequest requisicao) {
        Projeto projeto = carregar(id);
        projeto.setTipoProjeto(requisicao.tipoProjeto());
        projeto.setAnalistaResponsavel(resolverAnalista(requisicao.analistaResponsavelId()));
        projeto.setDataRecebimento(requisicao.dataRecebimento());
        projeto.setDataArt(requisicao.dataArt());
        return projetoRepository.save(projeto);
    }

    @Transactional
    public void excluir(Long id) {
        projetoRepository.delete(carregar(id));
    }

    /**
     * Registra a instalação física. Não mexe no status nem valida que o projeto já esteja
     * aprovado: na prática acontece instalar antes de a Coelba homologar, e travar isso só
     * faria o analista registrar a data errada em outro lugar.
     */
    @Transactional
    public Projeto registrarInstalacao(Long id, LocalDate dataInstalacao) {
        Projeto projeto = carregar(id);
        projeto.setDataInstalacao(dataInstalacao);
        return projetoRepository.save(projeto);
    }

    @Transactional
    public Projeto aguardarEnvio(Long id, Long usuarioId) {
        return transicionar(carregar(id), StatusProjeto.AGUARDANDO_ENVIO, usuarioId, true);
    }

    @Transactional
    public Projeto encaminhar(Long id, LocalDate dataArt, LocalDate dataEncaminhado,
            Long usuarioId) {
        Projeto projeto = carregar(id);
        exigirClienteSemDebito(projeto);
        if (dataArt != null) {
            projeto.setDataArt(dataArt);
        }
        projeto.setDataEncaminhado(dataEncaminhado == null ? LocalDate.now() : dataEncaminhado);
        return transicionar(projeto, StatusProjeto.ENCAMINHADO, usuarioId, true);
    }

    @Transactional
    public Projeto reencaminhar(Long id, LocalDate dataEncaminhado, Long usuarioId) {
        Projeto projeto = carregar(id);
        exigirClienteSemDebito(projeto);
        projeto.setDataEncaminhado(dataEncaminhado == null ? LocalDate.now() : dataEncaminhado);
        return transicionar(projeto, StatusProjeto.REENCAMINHADO, usuarioId, true);
    }

    /**
     * Etapa 2 do fluxo: "sem débito, o projeto é preenchido e enviado à Coelba". A guarda fica
     * aqui, e não no controller, para valer também quando a origem for a leitura de e-mail ou
     * um webhook do CRM.
     * <p>
     * Barra apenas o envio à Coelba. O projeto continua nascendo e existindo em RECEBIDO mesmo
     * com o cliente devendo — é assim que o gestor vê o cliente travado e o dashboard consegue
     * medir há quanto tempo, em vez de o cliente simplesmente desaparecer da tela.
     */
    private void exigirClienteSemDebito(Projeto projeto) {
        Long clienteId = projeto.getCliente().getId();
        if (debitoService.clienteTemDebitoAtivo(clienteId)) {
            throw new ClienteComDebitoException(clienteId);
        }
    }

    @Transactional
    public Projeto aprovar(Long id, LocalDate dataAprovacao, Long usuarioId) {
        Projeto projeto = carregar(id);
        projeto.setDataAprovacao(dataAprovacao == null ? LocalDate.now() : dataAprovacao);
        return transicionar(projeto, StatusProjeto.APROVADO, usuarioId, true);
    }

    @Transactional
    public Projeto reprovar(Long id, String motivo, Long usuarioId) {
        Projeto projeto = carregar(id);
        projeto.setMotivoReprova(motivo);
        return transicionar(projeto, StatusProjeto.REPROVADO, usuarioId, true);
    }

    /**
     * Entrada genérica, para origens que não são a tela — a leitura de e-mail da Coelba, por
     * exemplo. Continua validando a máquina de estados.
     */
    @Transactional
    public Projeto atualizarStatus(Long id, StatusProjeto novoStatus, Long usuarioId) {
        return transicionar(carregar(id), novoStatus, usuarioId, true);
    }

    /**
     * Correção administrativa: pula a validação de transição, mas continua auditando. A
     * justificativa vira parte do registro de auditoria via {@code motivoReprova} apenas
     * quando o destino é REPROVADO; nos outros casos ela fica no log e no histórico.
     */
    @Transactional
    public Projeto corrigirStatus(Long id, StatusProjeto novoStatus, String justificativa,
            Long usuarioId) {
        Projeto projeto = carregar(id);
        if (novoStatus == StatusProjeto.REPROVADO) {
            projeto.setMotivoReprova(justificativa);
        }
        return transicionar(projeto, novoStatus, usuarioId, false);
    }

    private Projeto nascerRecebido(Cliente cliente, TipoProjeto tipo, Usuario analista,
            LocalDate dataRecebimento, LocalDate dataArt, Long usuarioId) {

        Projeto projeto = Projeto.builder()
                .cliente(cliente)
                .tipoProjeto(tipo)
                .analistaResponsavel(analista)
                .status(StatusProjeto.RECEBIDO)
                .dataRecebimento(dataRecebimento)
                .dataArt(dataArt)
                .build();
        Projeto salvo = projetoRepository.save(projeto);

        eventPublisher.publishEvent(new ProjetoStatusChangedEvent(
                salvo.getId(), cliente.getId(), null, StatusProjeto.RECEBIDO,
                Instant.now(), usuarioId));

        return salvo;
    }

    private Projeto transicionar(Projeto projeto, StatusProjeto novoStatus, Long usuarioId,
            boolean validarTransicao) {

        StatusProjeto statusAnterior = projeto.getStatus();

        // Idempotente: repetir o status atual não republica evento, para duplo clique não
        // gerar duas linhas de histórico e distorcer as métricas.
        if (statusAnterior == novoStatus) {
            return projetoRepository.save(projeto);
        }
        if (validarTransicao && !statusAnterior.podeIrPara(novoStatus)) {
            throw new TransicaoStatusInvalidaException("Projeto", statusAnterior, novoStatus);
        }

        projeto.setStatus(novoStatus);

        // Redes de segurança para as métricas do dashboard: nenhum projeto pode ficar aprovado
        // sem data de aprovação, nem encaminhado sem data de envio.
        if (novoStatus == StatusProjeto.APROVADO && projeto.getDataAprovacao() == null) {
            projeto.setDataAprovacao(LocalDate.now());
        }
        if ((novoStatus == StatusProjeto.ENCAMINHADO || novoStatus == StatusProjeto.REENCAMINHADO)
                && projeto.getDataEncaminhado() == null) {
            projeto.setDataEncaminhado(LocalDate.now());
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

    private Cliente carregarCliente(Long clienteId) {
        return clienteRepository.findById(clienteId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Cliente não encontrado: " + clienteId));
    }

    private Usuario resolverAnalista(Long analistaId) {
        if (analistaId == null) {
            return null;
        }
        return usuarioRepository.findById(analistaId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Usuário não encontrado: " + analistaId));
    }

    private Projeto carregar(Long id) {
        return projetoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Projeto não encontrado: " + id));
    }
}
