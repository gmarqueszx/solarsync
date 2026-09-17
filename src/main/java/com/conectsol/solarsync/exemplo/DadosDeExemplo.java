package com.conectsol.solarsync.exemplo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.conectsol.solarsync.auth.NomePapel;
import com.conectsol.solarsync.auth.PapelRepository;
import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.cliente.ClienteService;
import com.conectsol.solarsync.cliente.dto.ClienteFiltro;
import com.conectsol.solarsync.cliente.dto.ClienteRequest;
import com.conectsol.solarsync.debito.DebitoService;
import com.conectsol.solarsync.debito.StatusDebito;
import com.conectsol.solarsync.debito.TipoDebito;
import com.conectsol.solarsync.debito.dto.DebitoRegistrarRequest;
import com.conectsol.solarsync.pendencia.PendenciaService;
import com.conectsol.solarsync.pendencia.StatusPendencia;
import com.conectsol.solarsync.pendencia.TipoPendencia;
import com.conectsol.solarsync.pendencia.dto.PendenciaCriarRequest;
import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.conectsol.solarsync.projeto.ProjetoService;
import com.conectsol.solarsync.projeto.TipoProjeto;
import com.conectsol.solarsync.projeto.dto.ProjetoAtualizarRequest;
import com.conectsol.solarsync.unificacao.UnificacaoService;
import com.conectsol.solarsync.unificacao.dto.UnificacaoRequest;
import com.conectsol.solarsync.vistoria.VistoriaService;

import lombok.RequiredArgsConstructor;

/**
 * Popula o banco com clientes de exemplo cobrindo todos os estados do fluxo, para dar o que ver
 * nas telas e no dashboard antes de a planilha real ser importada.
 * <p>
 * <b>Passa pelos services, e não por SQL, de propósito.</b> É o que faz os eventos de domínio
 * dispararem e o {@code historico_status} nascer povoado — inserindo direto no banco, as telas
 * teriam dados mas o dashboard não teria nada para agregar, e a automação
 * "pendência resolvida → cria projeto" não seria exercitada.
 * <p>
 * <b>Não é transacional de propósito</b>: o listener que cria o projeto roda em
 * {@code AFTER_COMMIT}, então cada chamada de service precisa commitar por conta.
 * <p>
 * Desligado por padrão. Habilite com {@code SOLARSYNC_DADOS_DE_EXEMPLO=true} e nunca em
 * produção. É idempotente: se os clientes de exemplo já existem, não faz nada. Para limpar,
 * o caminho mais simples em dev é recriar o banco (`docker compose down -v`).
 * <p>
 * As datas de negócio são retroativas para as métricas de tempo fazerem sentido. Já os
 * timestamps de {@code historico_status} são do momento da semeadura — as métricas que dependem
 * só deles ficarão perto de zero até haver movimento real.
 */
@Component
@ConditionalOnProperty("solarsync.dados-de-exemplo")
@RequiredArgsConstructor
public class DadosDeExemplo implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DadosDeExemplo.class);

    /** Marca de que a semeadura já rodou, para não duplicar a cada restart. */
    private static final String CLIENTE_MARCADOR = "Ana Paula Rocha (exemplo)";

    private final ClienteService clienteService;
    private final ClienteRepository clienteRepository;
    private final PendenciaService pendenciaService;
    private final ProjetoService projetoService;
    private final ProjetoRepository projetoRepository;
    private final DebitoService debitoService;
    private final VistoriaService vistoriaService;
    private final UnificacaoService unificacaoService;
    private final UsuarioRepository usuarioRepository;
    private final PapelRepository papelRepository;

    @Override
    public void run(ApplicationArguments argumentos) {
        if (clienteService.listar(
                new ClienteFiltro(CLIENTE_MARCADOR, null, null),
                org.springframework.data.domain.Pageable.ofSize(1)).hasContent()) {
            log.info("Dados de exemplo já presentes; nada a semear.");
            return;
        }

        Long admin = usuarioRepository.findByEmail("joaogabriel@conectsol.com")
                .map(Usuario::getId)
                .orElse(null);
        if (admin == null) {
            log.warn("Admin da migration V3 não encontrado; semeadura de exemplo abortada.");
            return;
        }

        List<Long> analistas = criarAnalistasDeExemplo();
        Long ivan = analistas.get(0);
        Long larissa = analistas.get(1);
        Long camila = analistas.get(2);

        // 1. Fila da triagem: ninguém checou a Coelba para estes clientes ainda. Alimenta a
        //    métrica de tempo sem interação e é a lista de trabalho da etapa 1.
        clienteService.criar(new ClienteRequest("Ana Paula Rocha (exemplo)", "Salvador",
                "Vendedor Bruno", hojeMenos(3), "3001234501", "(71) 99100-0001"));
        criarCliente("Hélio Santana (exemplo)", "Lauro de Freitas", 6);
        criarCliente("Vera Lúcia Pinto (exemplo)", "Salvador", 9);

        // 2. Pendência aberta, aguardando a Coelba.
        Long semAcao = criarCliente("Carlos Eduardo Lima (exemplo)", "Lauro de Freitas", 12);
        pendenciaService.criar(new PendenciaCriarRequest(semAcao, TipoPendencia.LIGACAO_NOVA,
                instanteMenos(10), ivan, "Aguardando vistoria de ligação nova"), admin);

        // 3. Pendência em andamento.
        Long emAndamento = criarCliente("Fernanda Souza (exemplo)", "Camaçari", 20);
        var pendenciaEmAndamento = pendenciaService.criar(new PendenciaCriarRequest(emAndamento,
                TipoPendencia.TROCA_TITULARIDADE, instanteMenos(18), larissa,
                "Documentação enviada à Coelba"), admin);
        pendenciaService.atualizarStatus(pendenciaEmAndamento.getId(),
                StatusPendencia.EM_ANDAMENTO, ivan, "Protocolo aberto na Coelba");

        // 4. Pendência cancelada — cliente desistiu.
        Long cancelado = criarCliente("Roberto Alves (exemplo)", "Salvador", 30);
        var pendenciaCancelada = pendenciaService.criar(new PendenciaCriarRequest(cancelado,
                TipoPendencia.EXTENSAO_REDE, instanteMenos(28), camila, null), admin);
        pendenciaService.atualizarStatus(pendenciaCancelada.getId(), StatusPendencia.CANCELADA,
                camila, "Cliente desistiu da instalação");

        // 5. Pendência travada por débito: a resolução é recusada com 409 até o cliente quitar.
        //    É o caso que a etapa 1 não conseguia representar antes.
        Long travadoPorDebito = criarCliente("Elaine Ribeiro (exemplo)", "Salvador", 22);
        pendenciaService.criar(new PendenciaCriarRequest(travadoPorDebito,
                TipoPendencia.LIGACAO_NOVA, instanteMenos(20), camila,
                "Resolução travada: cliente com débito na agência virtual"), admin);
        consultar(travadoPorDebito, TipoDebito.PENDENCIA, StatusDebito.ATIVO, 19, camila);

        // 6. Pendência resolvida: exigiu a consulta de débito de pendência antes, e a automação
        //    criou o projeto em RECEBIDO.
        Long resolvido = criarCliente("Juliana Martins (exemplo)", "Feira de Santana", 25);
        var pendenciaResolvida = pendenciaService.criar(new PendenciaCriarRequest(resolvido,
                TipoPendencia.TROCA_TITULARIDADE, instanteMenos(24), ivan, null), admin);
        consultar(resolvido, TipoDebito.PENDENCIA, StatusDebito.QUITADO, 23, ivan);
        pendenciaService.atualizarStatus(pendenciaResolvida.getId(), StatusPendencia.RESOLVIDA,
                ivan, "Titularidade transferida");

        // 7. Cliente com débito de homologação: o projeto existe e está travado (encaminhar dá
        //    409 CLIENTE_COM_DEBITO). É o caso que o financeiro precisa ver na tela de débitos.
        Long comDebito = criarCliente("Marcos Vinícius Dias (exemplo)", "Salvador", 40);
        Projeto projetoTravado = projetoSemPendencia(comDebito, TipoProjeto.PROJETO_INICIAL,
                larissa, hojeMenos(35), null, "8.40", admin);
        consultar(comDebito, TipoDebito.HOMOLOGACAO, StatusDebito.ATIVO, 34, larissa);

        // 8. Débito quitado e projeto já encaminhado à Coelba.
        Long quitado = criarCliente("Patrícia Nunes (exemplo)", "Camaçari", 60);
        Projeto projetoEncaminhado = projetoSemPendencia(quitado, TipoProjeto.AMPLIACAO, camila,
                hojeMenos(55), hojeMenos(50), "12.60", admin);
        consultar(quitado, TipoDebito.HOMOLOGACAO, StatusDebito.ATIVO, 54, camila);
        consultar(quitado, TipoDebito.HOMOLOGACAO, StatusDebito.QUITADO, 48, camila);
        projetoService.encaminhar(projetoEncaminhado.getId(), hojeMenos(50), hojeMenos(47),
                "2026-COE-004781", camila);

        // 9. Projeto reprovado pela Coelba, aguardando correção.
        Long reprovado = criarCliente("Diego Ferreira (exemplo)", "Salvador", 70);
        Projeto projetoReprovado = projetoSemPendencia(reprovado, TipoProjeto.AMPLIACAO,
                ivan, hojeMenos(65), hojeMenos(62), "5.20", admin);
        consultar(reprovado, TipoDebito.HOMOLOGACAO, StatusDebito.QUITADO, 63, ivan);
        projetoService.encaminhar(projetoReprovado.getId(), hojeMenos(62), hojeMenos(60),
                "2026-COE-004802", ivan);
        projetoService.reprovar(projetoReprovado.getId(),
                "Diagrama unifilar sem ART do responsável técnico", ivan);

        // 10. Reencaminhado após correção e aprovado.
        Long reencaminhado = criarCliente("Luciana Barbosa (exemplo)", "Feira de Santana", 90);
        Projeto projetoAprovado = projetoSemPendencia(reencaminhado, TipoProjeto.CORRECAO,
                larissa, hojeMenos(85), hojeMenos(82), "24.00", admin);
        consultar(reencaminhado, TipoDebito.HOMOLOGACAO, StatusDebito.QUITADO, 83, larissa);
        projetoService.encaminhar(projetoAprovado.getId(), hojeMenos(82), hojeMenos(80),
                "2026-COE-004655", larissa);
        projetoService.reprovar(projetoAprovado.getId(), "Divergência na potência declarada",
                larissa);
        projetoService.reencaminhar(projetoAprovado.getId(), hojeMenos(70), null, larissa);
        projetoService.aprovar(projetoAprovado.getId(), hojeMenos(62), larissa);

        // 11. Aprovado e instalado, esperando vistoria: a fila de trabalho da etapa 4.
        Long instalado = criarCliente("Thiago Ramos (exemplo)", "Salvador", 120);
        Projeto projetoInstalado = projetoSemPendencia(instalado, TipoProjeto.PROJETO_INICIAL,
                camila, hojeMenos(115), hojeMenos(112), "10.80", admin);
        consultar(instalado, TipoDebito.HOMOLOGACAO, StatusDebito.QUITADO, 113, camila);
        projetoService.encaminhar(projetoInstalado.getId(), hojeMenos(112), hojeMenos(110),
                "2026-COE-004390", camila);
        projetoService.aprovar(projetoInstalado.getId(), hojeMenos(95), camila);
        projetoService.registrarInstalacao(projetoInstalado.getId(), hojeMenos(20));

        // 11b. Aprovado e ainda sem instalação: é o que a fila "Aguardando vistoria" mostra
        //      esperando alguém registrar a data de instalação.
        Long aprovadoSemInstalacao = criarCliente("Rafael Monteiro (exemplo)", "Camaçari", 80);
        Projeto projetoSemInstalacao = projetoSemPendencia(aprovadoSemInstalacao,
                TipoProjeto.PROJETO_INICIAL, ivan, hojeMenos(75), hojeMenos(72), "9.90", admin);
        consultar(aprovadoSemInstalacao, TipoDebito.HOMOLOGACAO, StatusDebito.QUITADO, 73, ivan);
        projetoService.encaminhar(projetoSemInstalacao.getId(), hojeMenos(72), hojeMenos(70),
                "2026-COE-004912", ivan);
        projetoService.aprovar(projetoSemInstalacao.getId(), hojeMenos(52), ivan);

        // 12. Ciclo completo: vistoria reprovada, resolicitada e aprovada.
        Long cicloCompleto = criarCliente("Sandra Oliveira (exemplo)", "Camaçari", 150);
        Projeto projetoCompleto = projetoSemPendencia(cicloCompleto,
                TipoProjeto.AMPLIACAO, ivan, hojeMenos(145), hojeMenos(142), "15.40",
                admin);
        consultar(cicloCompleto, TipoDebito.HOMOLOGACAO, StatusDebito.QUITADO, 143, ivan);
        projetoService.encaminhar(projetoCompleto.getId(), hojeMenos(142), hojeMenos(140),
                "2026-COE-003118", ivan);
        projetoService.aprovar(projetoCompleto.getId(), hojeMenos(120), ivan);
        projetoService.registrarInstalacao(projetoCompleto.getId(), hojeMenos(90));
        var vistoria = vistoriaService.solicitar(projetoCompleto.getId(), hojeMenos(85), ivan);
        vistoriaService.reprovar(vistoria.getId(), hojeMenos(75), ivan);
        vistoriaService.resolicitar(vistoria.getId(), hojeMenos(60), ivan);
        vistoriaService.aprovar(vistoria.getId(), hojeMenos(45), ivan);

        // 12b. Projeto pronto e ninguém consultou o débito de homologação ainda: encaminhar dá
        //      409 DEBITO_NAO_CONSULTADO. É a fila de trabalho do projetista ao receber o
        //      cliente, e o caso que antes passava batido como "sem débito".
        Long aguardando = criarCliente("Gustavo Pereira (exemplo)", "Salvador", 45);
        Projeto projetoAguardando = projetoSemPendencia(aguardando,
                TipoProjeto.CORRECAO, larissa, hojeMenos(40), null, "6.60", admin);
        projetoService.aguardarEnvio(projetoAguardando.getId(), larissa);

        // 13. Unificações: uma na fila, uma feita esperando desligamento, uma concluída.
        //     Cliente que chegou à unificação passou pela triagem lá atrás — daí o
        //     `clienteTriado`, que registra isso em vez de deixá-los na fila de verificação.
        Long unificar = clienteTriado("Família Andrade (exemplo)", "Feira de Santana", 100, admin);
        unificacaoService.criar(new UnificacaoRequest(unificar, null, camila,
                "Duas UCs no mesmo terreno; unificar na maior"));

        Long unificado = clienteTriado("Condomínio Sol Nascente (exemplo)", "Salvador", 130, admin);
        var unificacaoFeita = unificacaoService.criar(new UnificacaoRequest(unificado, null, ivan,
                "Unificação da área comum"));
        unificacaoService.marcarFeita(unificacaoFeita.getId(), true);

        Long desligado = clienteTriado("Mariana Castro (exemplo)", "Camaçari", 160, admin);
        var unificacaoConcluida = unificacaoService.criar(new UnificacaoRequest(desligado, null,
                larissa, "Unificação com desligamento do medidor antigo"));
        unificacaoService.marcarFeita(unificacaoConcluida.getId(), true);
        unificacaoService.solicitarDesligamento(unificacaoConcluida.getId(), hojeMenos(20), admin);
        unificacaoService.concluirDesligamento(unificacaoConcluida.getId(), hojeMenos(6), admin);

        // 14. Desligamento pedido e ainda sem retorno — a fila que se perde de vista.
        Long aguardandoDesligamento = clienteTriado("Sítio Boa Vista (exemplo)", "Camaçari", 140,
                admin);
        var unificacaoAguardando = unificacaoService.criar(new UnificacaoRequest(
                aguardandoDesligamento, null, camila, "Aguardando desligamento do medidor antigo"));
        unificacaoService.marcarFeita(unificacaoAguardando.getId(), true);
        unificacaoService.solicitarDesligamento(unificacaoAguardando.getId(), hojeMenos(12), admin);

        // 15. Equipe de campo não realizou o desligamento; abriu-se O.S.
        Long comOs = clienteTriado("Pousada do Vale (exemplo)", "Lauro de Freitas", 170, admin);
        var unificacaoComOs = unificacaoService.criar(new UnificacaoRequest(
                comOs, null, ivan, "Equipe não conseguiu acesso ao padrão; O.S. aberta"));
        unificacaoService.marcarFeita(unificacaoComOs.getId(), true);
        unificacaoService.solicitarDesligamento(unificacaoComOs.getId(), hojeMenos(45), admin);
        unificacaoService.abrirOrdemDeServico(unificacaoComOs.getId(), admin);

        log.warn("Dados de exemplo semeados: {} clientes, {} projetos. "
                + "Desabilite SOLARSYNC_DADOS_DE_EXEMPLO antes de usar este banco pra valer.",
                clienteRepository.count(), projetoRepository.count());
        log.info("Projeto travado por débito: id {} | instalado sem vistoria: id {} | "
                + "aprovado esperando instalação: id {}",
                projetoTravado.getId(), projetoInstalado.getId(), projetoSemInstalacao.getId());
    }

    /**
     * Uma consulta de débito na agência virtual, retroativa. O {@code consultadoEm} é a data em
     * que o analista constatou a situação, não a da semeadura — sem isso a métrica de tempo
     * parado por débito nasceria zerada.
     */
    private void consultar(Long clienteId, TipoDebito tipo, StatusDebito status, int diasAtras,
            Long usuarioId) {
        debitoService.registrarConsulta(clienteId,
                new DebitoRegistrarRequest(tipo, status, instanteMenos(diasAtras)), usuarioId);
    }

    /**
     * Analistas de exemplo para as telas mostrarem responsável. E-mail em domínio não permitido
     * e sem senha: não conseguem entrar por nenhum dos dois fluxos de login, são só dado.
     */
    private List<Long> criarAnalistasDeExemplo() {
        var papelAnalista = papelRepository.findByNome(NomePapel.ANALISTA).orElseThrow();
        return List.of("Ivan", "Larissa", "Camila").stream()
                .map(nome -> usuarioRepository
                        .findByEmail(nome.toLowerCase() + ".exemplo@exemplo.test")
                        .orElseGet(() -> usuarioRepository.save(Usuario.builder()
                                .nome(nome + " (exemplo)")
                                .email(nome.toLowerCase() + ".exemplo@exemplo.test")
                                .ativo(true)
                                .papeis(Set.of(papelAnalista))
                                .build()))
                        .getId())
                .toList();
    }

    /**
     * O caminho "segue direto" da etapa 1, do jeito que a interface faz: a triagem conclui que
     * não há pendência, o {@code TriagemSemPendenciaListener} faz o projeto nascer em RECEBIDO,
     * e só depois se preenchem tipo, analista e potência.
     * <p>
     * Passar por aqui em vez de chamar {@code projetoService.criar} é o que exercita a
     * automação nova e deixa o cliente com {@code statusTriagem = SEM_PENDENCIA}. Criando o
     * projeto direto, o cliente ficaria eternamente na fila de verificação com um projeto
     * aprovado — a fila nasceria mentindo, que é justamente o que ela deveria resolver.
     */
    private Projeto projetoSemPendencia(Long clienteId, TipoProjeto tipo, Long analistaId,
            LocalDate dataRecebimento, LocalDate dataArt, String potenciaKwp, Long usuarioId) {

        clienteService.marcarSemPendencia(clienteId, usuarioId);

        // O listener roda em AFTER_COMMIT, então o projeto já existe quando a chamada retorna.
        Projeto projeto = projetoRepository.findFirstByClienteIdOrderByCriadoEmDesc(clienteId)
                .orElseThrow(() -> new IllegalStateException(
                        "Triagem sem pendência não criou projeto para o cliente " + clienteId));

        return projetoService.atualizar(projeto.getId(), new ProjetoAtualizarRequest(
                tipo, analistaId, dataRecebimento, dataArt, null, new BigDecimal(potenciaKwp)));
    }

    private int sequencialUc = 2;

    /** UC e telefone sintéticos, para as telas mostrarem os campos preenchidos. */
    private Long criarCliente(String nome, String cidade, int diasAtras) {
        String uc = "30012345%02d".formatted(sequencialUc);
        String telefone = "(71) 99100-%04d".formatted(sequencialUc);
        sequencialUc++;
        return clienteService.criar(new ClienteRequest(
                nome, cidade, "Vendedor Bruno", hojeMenos(diasAtras), uc, telefone)).id();
    }

    /**
     * Cliente já triado, sem projeto. Para os casos de unificação, que na operação real chegam
     * ali muito depois da etapa 1 — deixá-los na fila de verificação encheria a fila da triagem
     * de clientes que ninguém precisa checar.
     */
    private Long clienteTriado(String nome, String cidade, int diasAtras, Long usuarioId) {
        Long clienteId = criarCliente(nome, cidade, diasAtras);
        clienteService.marcarSemPendencia(clienteId, usuarioId);
        return clienteId;
    }

    private static LocalDate hojeMenos(int dias) {
        return LocalDate.now().minusDays(dias);
    }

    private static Instant instanteMenos(int dias) {
        return Instant.now().minus(dias, ChronoUnit.DAYS);
    }
}
