package com.conectsol.solarsync.projeto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import com.conectsol.solarsync.common.FusoDaOperacao;
import com.conectsol.solarsync.common.exception.ClienteComDebitoException;
import com.conectsol.solarsync.common.exception.ClienteSomentePendenciaException;
import com.conectsol.solarsync.common.exception.DebitoNaoConsultadoException;
import com.conectsol.solarsync.common.exception.NumeroSolicitacaoObrigatorioException;
import com.conectsol.solarsync.common.exception.ProximoDebitoAVencerException;
import com.conectsol.solarsync.common.exception.TransicaoStatusInvalidaException;
import com.conectsol.solarsync.common.web.PrioridadePrimeiro;
import com.conectsol.solarsync.debito.DebitoService;
import com.conectsol.solarsync.debito.TipoDebito;
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
        return projetoRepository.findAll(ProjetoSpecs.de(filtro),
                PrioridadePrimeiro.aplicar(paginacao, "cliente.prioridade"));
    }

    @Transactional(readOnly = true)
    public Projeto buscar(Long id) {
        return carregar(id);
    }

    /**
     * Reage à resolução de uma pendência: cria um novo Projeto com status RECEBIDO para o
     * cliente, a menos que já exista um projeto em andamento (RECEBIDO/AGUARDANDO_ENVIO)
     * para evitar duplicidade quando várias pendências do mesmo cliente são resolvidas.
     * O tipo começa como PROJETO_INICIAL e pode ser ajustado pelo analista responsável.
     * <p>
     * Devolve {@code null} para o cliente de fluxo curto ("somente pendência"): ali a resolução da
     * pendência <b>encerra</b> o processo em vez de abrir o próximo. Como este é o único caminho
     * automático de criação de projeto, a guarda aqui cobre os dois listeners de uma vez — o da
     * pendência resolvida e o da triagem sem pendência — e cobriria também um terceiro que
     * aparecesse depois.
     */
    @Transactional
    public Projeto criarOuAtivarProjetoParaCliente(Long clienteId, Long usuarioId) {
        Cliente cliente = carregarCliente(clienteId);
        if (cliente.isSomentePendencia()) {
            return null;
        }
        List<Projeto> projetosDoCliente = projetoRepository.findByClienteId(clienteId);
        return projetosDoCliente.stream()
                .filter(projeto -> STATUS_EM_ANDAMENTO.contains(projeto.getStatus()))
                .findFirst()
                .orElseGet(() -> nascerRecebido(
                        cliente, TipoProjeto.PROJETO_INICIAL, null,
                        LocalDate.now(), null, null, null, usuarioId));
    }

    @Transactional
    public Projeto criar(ProjetoCriarRequest requisicao, Long usuarioId) {
        Cliente cliente = carregarCliente(requisicao.clienteId());
        // Vale também para a criação à mão, e não só para a automática: senão o fluxo curto seria
        // contornado pelo botão "Novo Projeto" sem ninguém notar que o cliente era avulso.
        if (cliente.isSomentePendencia()) {
            throw new ClienteSomentePendenciaException(cliente.getId());
        }
        return nascerRecebido(
                cliente,
                requisicao.tipoProjeto(),
                resolverAnalista(requisicao.analistaResponsavelId()),
                requisicao.dataRecebimento() == null ? LocalDate.now() : requisicao.dataRecebimento(),
                requisicao.dataArt(),
                requisicao.numeroSolicitacao(),
                requisicao.potenciaKwp(),
                usuarioId);
    }

    @Transactional
    public Projeto atualizar(Long id, ProjetoAtualizarRequest requisicao) {
        Projeto projeto = carregar(id);
        projeto.setTipoProjeto(requisicao.tipoProjeto());
        projeto.setAnalistaResponsavel(resolverAnalista(requisicao.analistaResponsavelId()));
        projeto.setDataRecebimento(requisicao.dataRecebimento());
        projeto.setDataArt(requisicao.dataArt());
        // O PUT substitui o cadastro inteiro, mas o número da solicitação é a exceção: pode ser
        // corrigido, nunca apagado. Sem isso, o número obrigatório no envio seria contornável
        // por uma edição — e um projeto já enviado ficaria sem a chave que casa o retorno por
        // e-mail com ele, silenciosamente.
        if (requisicao.numeroSolicitacao() != null && !requisicao.numeroSolicitacao().isBlank()) {
            projeto.setNumeroSolicitacao(requisicao.numeroSolicitacao().trim());
        }
        projeto.setPotenciaKwp(requisicao.potenciaKwp());
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

    /**
     * Desce para o projeto do cliente a data de instalação declarada num pedido de prioridade por
     * instalação adiantada. Chamado pelo {@code ClienteService} logo depois de gravar a prioridade.
     * <p>
     * É o que evita um segundo campo de data de instalação vivendo em paralelo: a partir daqui quem
     * manda é {@code projeto.data_instalacao}, que é o campo que a etapa de vistoria já lê e exige
     * — a prioridade só o alimenta.
     * <p>
     * <b>Não sobrescreve</b> data já registrada: quem instalou de fato e anotou na etapa de vistoria
     * sabe mais que um pedido de prioridade que pode ser de semanas atrás. E não mexe em status: a
     * instalação é evento de campo, não etapa da homologação.
     */
    @Transactional
    public void registrarInstalacaoDeclaradaNaPrioridade(Long clienteId, LocalDate dataInstalacao) {
        if (dataInstalacao == null) {
            return;
        }
        projetoRepository.findByClienteId(clienteId).stream()
                .filter(projeto -> projeto.getDataInstalacao() == null)
                .forEach(projeto -> {
                    projeto.setDataInstalacao(dataInstalacao);
                    projetoRepository.save(projeto);
                });
    }

    @Transactional
    public Projeto aguardarEnvio(Long id, Long usuarioId) {
        return transicionar(carregar(id), StatusProjeto.AGUARDANDO_ENVIO, usuarioId, true);
    }

    @Transactional
    public Projeto encaminhar(Long id, LocalDate dataArt, LocalDate dataEncaminhado,
            String numeroSolicitacao, Long usuarioId) {
        Projeto projeto = carregar(id);
        exigirClienteSemDebito(projeto);
        if (dataArt != null) {
            projeto.setDataArt(dataArt);
        }
        aplicarNumeroSolicitacao(projeto, numeroSolicitacao);
        projeto.setDataEncaminhado(dataEncaminhado == null ? LocalDate.now() : dataEncaminhado);
        return transicionar(projeto, StatusProjeto.ENCAMINHADO, usuarioId, true);
    }

    @Transactional
    public Projeto reencaminhar(Long id, LocalDate dataEncaminhado, String numeroSolicitacao,
            Long usuarioId) {
        Projeto projeto = carregar(id);
        exigirClienteSemDebito(projeto);
        aplicarNumeroSolicitacao(projeto, numeroSolicitacao);
        projeto.setDataEncaminhado(dataEncaminhado == null ? LocalDate.now() : dataEncaminhado);
        return transicionar(projeto, StatusProjeto.REENCAMINHADO, usuarioId, true);
    }

    /**
     * O número da solicitação é <b>obrigatório</b> para enviar à Coelba (decisão do usuário em
     * 17/09/2026). A guarda fica aqui, e não só no {@code @NotBlank} do DTO, pela mesma razão da
     * guarda de débito: vale também quando a origem for a importação da planilha ou uma
     * integração, e não apenas a tela.
     * <p>
     * Sem o número, o projeto vai à Coelba sem a chave que casa o retorno por e-mail com ele
     * (seção 9) — a automação da etapa 3 não teria como saber de que projeto o e-mail fala, e
     * cairia em {@code SEM_CORRESPONDENCIA}. Era opcional antes, sob o argumento de que o
     * retorno da Coelba às vezes demora; o que isso produzia era projeto enviado sem chave.
     * <p>
     * Vale também no reenvio: é justamente ali que a Coelba pode devolver outro número, e
     * aceitar vazio manteria o número do ciclo anterior — pior que recusar, porque o e-mail novo
     * casaria com um número velho.
     */
    private void aplicarNumeroSolicitacao(Projeto projeto, String numeroSolicitacao) {
        if (numeroSolicitacao == null || numeroSolicitacao.isBlank()) {
            throw new NumeroSolicitacaoObrigatorioException();
        }
        projeto.setNumeroSolicitacao(numeroSolicitacao.trim());
    }

    /**
     * Etapa 2 do fluxo: "sem débito, o projeto é preenchido e enviado à Coelba". A guarda fica
     * aqui, e não no controller, para valer também quando a origem for a leitura de e-mail ou
     * um webhook do CRM.
     * <p>
     * Barra apenas o envio à Coelba. O projeto continua nascendo e existindo em RECEBIDO mesmo
     * com o cliente devendo — é assim que o gestor vê o cliente travado e o dashboard consegue
     * medir há quanto tempo, em vez de o cliente simplesmente desaparecer da tela.
     * <p>
     * Olha <b>só</b> o débito do tipo {@code HOMOLOGACAO}, e exige que ele tenha sido
     * consultado. É o passo real do projetista: concluída a pendência, o cliente cai com ele,
     * que consulta a agência virtual para ver se há débito impedindo a homologação. Antes esta
     * consulta era cobrada de quem trabalhava a pendência, que na prática não a faz — e aqui
     * "sem consulta" contava como sem débito, o que deixava passar exatamente o cliente que
     * ninguém tinha olhado.
     */
    private void exigirClienteSemDebito(Projeto projeto) {
        Long clienteId = projeto.getCliente().getId();
        if (!debitoService.clienteTemConsultaRegistrada(clienteId, TipoDebito.HOMOLOGACAO)) {
            throw new DebitoNaoConsultadoException(clienteId, TipoDebito.HOMOLOGACAO);
        }
        if (debitoService.clienteTemDebitoAtivo(clienteId, TipoDebito.HOMOLOGACAO)) {
            throw new ClienteComDebitoException(clienteId, "encaminhar o projeto");
        }

        // Terceira recusa, e a que não se enxerga olhando só o status do débito: o cliente está
        // quitado agora, mas a próxima conta vence em um dia ou menos. A Coelba não analisa o
        // projeto no instante em que ele chega — quando analisar, o débito já existe e o projeto
        // volta reprovado. Recusar aqui é mais barato que reenviar depois.
        LocalDate naIminencia =
                debitoService.proximoDebitoNaIminencia(clienteId, TipoDebito.HOMOLOGACAO);
        if (naIminencia != null) {
            throw new ProximoDebitoAVencerException(clienteId, naIminencia,
                    ChronoUnit.DAYS.between(FusoDaOperacao.hoje(), naIminencia));
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
            LocalDate dataRecebimento, LocalDate dataArt, String numeroSolicitacao,
            java.math.BigDecimal potenciaKwp, Long usuarioId) {

        Projeto projeto = Projeto.builder()
                .cliente(cliente)
                .tipoProjeto(tipo)
                .analistaResponsavel(analista)
                .status(StatusProjeto.RECEBIDO)
                .dataRecebimento(dataRecebimento)
                .dataArt(dataArt)
                .numeroSolicitacao(numeroSolicitacao)
                .potenciaKwp(potenciaKwp)
                // A outra metade da propagação da prioridade por instalação adiantada: quando ela
                // foi pedida na triagem, ainda não havia projeto para receber a data, e ela ficou
                // esperando no cliente. O projeto nasce já sabendo dela, e a etapa de vistoria a
                // encontra onde sempre a procurou.
                .dataInstalacao(cliente.getPrioridadeDataInstalacao())
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
