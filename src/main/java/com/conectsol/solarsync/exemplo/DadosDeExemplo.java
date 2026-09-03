package com.conectsol.solarsync.exemplo;

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
import com.conectsol.solarsync.cliente.dto.ClienteRequest;
import com.conectsol.solarsync.debito.DebitoService;
import com.conectsol.solarsync.debito.StatusDebito;
import com.conectsol.solarsync.debito.dto.DebitoRegistrarRequest;
import com.conectsol.solarsync.pendencia.PendenciaService;
import com.conectsol.solarsync.pendencia.StatusPendencia;
import com.conectsol.solarsync.pendencia.TipoPendencia;
import com.conectsol.solarsync.pendencia.dto.PendenciaCriarRequest;
import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.conectsol.solarsync.projeto.ProjetoService;
import com.conectsol.solarsync.projeto.TipoProjeto;
import com.conectsol.solarsync.projeto.dto.ProjetoCriarRequest;
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
        if (clienteRepository.findByNomeContainingIgnoreCase(CLIENTE_MARCADOR,
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

        // 1. Cliente recém-pago, ninguém tocou ainda: alimenta a métrica de tempo sem interação.
        clienteService.criar(new ClienteRequest(
                "Ana Paula Rocha (exemplo)", "Salvador", "Vendedor Bruno", hojeMenos(3)));

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

        // 5. Pendência resolvida: a automação cria o projeto em RECEBIDO.
        Long resolvido = criarCliente("Juliana Martins (exemplo)", "Feira de Santana", 25);
        var pendenciaResolvida = pendenciaService.criar(new PendenciaCriarRequest(resolvido,
                TipoPendencia.TROCA_TITULARIDADE, instanteMenos(24), ivan, null), admin);
        pendenciaService.atualizarStatus(pendenciaResolvida.getId(), StatusPendencia.RESOLVIDA,
                ivan, "Titularidade transferida");

        // 6. Cliente com débito: projeto existe e está travado (encaminhar dá 409).
        Long comDebito = criarCliente("Marcos Vinícius Dias (exemplo)", "Salvador", 40);
        Projeto projetoTravado = projetoService.criar(new ProjetoCriarRequest(
                comDebito, TipoProjeto.PADRAO, larissa, hojeMenos(35), null), admin);
        debitoService.registrarConsulta(comDebito,
                new DebitoRegistrarRequest(StatusDebito.ATIVO, instanteMenos(34)), larissa);

        // 7. Débito quitado e projeto já encaminhado à Coelba.
        Long quitado = criarCliente("Patrícia Nunes (exemplo)", "Camaçari", 60);
        Projeto projetoEncaminhado = projetoService.criar(new ProjetoCriarRequest(
                quitado, TipoProjeto.AMPLIACAO, camila, hojeMenos(55), hojeMenos(50)), admin);
        debitoService.registrarConsulta(quitado,
                new DebitoRegistrarRequest(StatusDebito.ATIVO, instanteMenos(54)), camila);
        debitoService.registrarConsulta(quitado,
                new DebitoRegistrarRequest(StatusDebito.QUITADO, instanteMenos(48)), camila);
        projetoService.encaminhar(projetoEncaminhado.getId(), hojeMenos(50), hojeMenos(47), camila);

        // 8. Projeto reprovado pela Coelba, aguardando correção.
        Long reprovado = criarCliente("Diego Ferreira (exemplo)", "Salvador", 70);
        Projeto projetoReprovado = projetoService.criar(new ProjetoCriarRequest(
                reprovado, TipoProjeto.AUMENTO_POTENCIA, ivan, hojeMenos(65), hojeMenos(62)),
                admin);
        projetoService.encaminhar(projetoReprovado.getId(), hojeMenos(62), hojeMenos(60), ivan);
        projetoService.reprovar(projetoReprovado.getId(),
                "Diagrama unifilar sem ART do responsável técnico", ivan);

        // 9. Reencaminhado após correção e aprovado.
        Long reencaminhado = criarCliente("Luciana Barbosa (exemplo)", "Feira de Santana", 90);
        Projeto projetoAprovado = projetoService.criar(new ProjetoCriarRequest(
                reencaminhado, TipoProjeto.MUDANCA_INVERSOR, larissa, hojeMenos(85),
                hojeMenos(82)), admin);
        projetoService.encaminhar(projetoAprovado.getId(), hojeMenos(82), hojeMenos(80), larissa);
        projetoService.reprovar(projetoAprovado.getId(), "Divergência na potência declarada",
                larissa);
        projetoService.reencaminhar(projetoAprovado.getId(), hojeMenos(70), larissa);
        projetoService.aprovar(projetoAprovado.getId(), hojeMenos(62), larissa);

        // 10. Instalado e esperando vistoria: a fila de trabalho da etapa 4.
        Long instalado = criarCliente("Thiago Ramos (exemplo)", "Salvador", 120);
        Projeto projetoInstalado = projetoService.criar(new ProjetoCriarRequest(
                instalado, TipoProjeto.PADRAO, camila, hojeMenos(115), hojeMenos(112)), admin);
        projetoService.encaminhar(projetoInstalado.getId(), hojeMenos(112), hojeMenos(110),
                camila);
        projetoService.aprovar(projetoInstalado.getId(), hojeMenos(95), camila);
        projetoService.registrarInstalacao(projetoInstalado.getId(), hojeMenos(20));

        // 11. Ciclo completo: vistoria reprovada, resolicitada e aprovada.
        Long cicloCompleto = criarCliente("Sandra Oliveira (exemplo)", "Camaçari", 150);
        Projeto projetoCompleto = projetoService.criar(new ProjetoCriarRequest(
                cicloCompleto, TipoProjeto.INVERSORES_SEPARADOS, ivan, hojeMenos(145),
                hojeMenos(142)), admin);
        projetoService.encaminhar(projetoCompleto.getId(), hojeMenos(142), hojeMenos(140), ivan);
        projetoService.aprovar(projetoCompleto.getId(), hojeMenos(120), ivan);
        projetoService.registrarInstalacao(projetoCompleto.getId(), hojeMenos(90));
        var vistoria = vistoriaService.solicitar(projetoCompleto.getId(), hojeMenos(85), ivan);
        vistoriaService.reprovar(vistoria.getId(), hojeMenos(75), ivan);
        vistoriaService.resolicitar(vistoria.getId(), hojeMenos(60), ivan);
        vistoriaService.aprovar(vistoria.getId(), hojeMenos(45), ivan);

        // 12. Projeto aguardando envio por motivo operacional (não é débito).
        Long aguardando = criarCliente("Gustavo Pereira (exemplo)", "Salvador", 45);
        Projeto projetoAguardando = projetoService.criar(new ProjetoCriarRequest(
                aguardando, TipoProjeto.PROJETO_UMA_PLACA_A_MAIS, larissa, hojeMenos(40), null),
                admin);
        projetoService.aguardarEnvio(projetoAguardando.getId(), larissa);

        // 13. Unificações: uma na fila, uma feita esperando desligamento, uma concluída.
        Long unificar = criarCliente("Família Andrade (exemplo)", "Feira de Santana", 100);
        unificacaoService.criar(new UnificacaoRequest(unificar, null, camila,
                "Duas UCs no mesmo terreno; unificar na maior"));

        Long unificado = criarCliente("Condomínio Sol Nascente (exemplo)", "Salvador", 130);
        var unificacaoFeita = unificacaoService.criar(new UnificacaoRequest(unificado, null, ivan,
                "Unificação da área comum"));
        unificacaoService.marcarFeita(unificacaoFeita.getId(), true);

        Long desligado = criarCliente("Mariana Castro (exemplo)", "Camaçari", 160);
        var unificacaoConcluida = unificacaoService.criar(new UnificacaoRequest(desligado, null,
                larissa, "Unificação com desligamento do medidor antigo"));
        unificacaoService.marcarFeita(unificacaoConcluida.getId(), true);
        unificacaoService.marcarDesligamento(unificacaoConcluida.getId(), true);

        log.warn("Dados de exemplo semeados: {} clientes, {} projetos. "
                + "Desabilite SOLARSYNC_DADOS_DE_EXEMPLO antes de usar este banco pra valer.",
                clienteRepository.count(), projetoRepository.count());
        log.info("Projeto travado por débito: id {} | instalado sem vistoria: id {}",
                projetoTravado.getId(), projetoInstalado.getId());
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

    private Long criarCliente(String nome, String cidade, int diasAtras) {
        return clienteService.criar(
                new ClienteRequest(nome, cidade, "Vendedor Bruno", hojeMenos(diasAtras))).id();
    }

    private static LocalDate hojeMenos(int dias) {
        return LocalDate.now().minusDays(dias);
    }

    private static Instant instanteMenos(int dias) {
        return Instant.now().minus(dias, ChronoUnit.DAYS);
    }
}
