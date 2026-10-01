package com.conectsol.solarsync.integracao.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.common.AbstractIntegrationTest;
import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.historico.HistoricoStatus;
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.integracao.UsuarioIntegracao;
import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.conectsol.solarsync.projeto.StatusProjeto;
import com.conectsol.solarsync.projeto.TipoProjeto;

/**
 * Prova ponta a ponta da automação da etapa 3 (seção 9 do CLAUDE.md): o retorno da Coelba lido
 * de um e-mail muda o status do projeto pelo mesmo caminho da tela, com auditoria em
 * historico_status — e, nos casos em que não deve mudar nada, não muda.
 * <p>
 * Entra pelo {@link RetornoCoelbaService} e não pelo job: o job é transporte (Gmail), e a regra
 * de negócio é o casamento e a aplicação. Assim o teste não precisa de servidor HTTP nem de
 * credencial do Google.
 */
class RetornoCoelbaIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId FUSO = ZoneId.of("America/Bahia");

    @Autowired
    private RetornoCoelbaService retornoCoelbaService;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private ProjetoRepository projetoRepository;

    @Autowired
    private EmailCoelbaRepository emailCoelbaRepository;

    @Autowired
    private HistoricoStatusRepository historicoStatusRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    /**
     * Histórico antes do resto (a FK de usuario_id), e nunca os usuários: a conta de integração é
     * semeada pela V13 e apagá-la contaminaria os outros testes que compartilham o contêiner.
     */
    @AfterEach
    void limpar() {
        historicoStatusRepository.deleteAll();
        emailCoelbaRepository.deleteAll();
        projetoRepository.deleteAll();
        clienteRepository.deleteAll();
    }

    @Test
    void emailDeAprovacaoAprovaOProjetoComADataDoEmailEAuditaAMudanca() {
        Projeto projeto = projetoEncaminhado("4410023");
        Instant recebidoEm = Instant.parse("2026-09-16T23:30:00Z");

        ResultadoProcessamento resultado = retornoCoelbaService.processar(new MensagemGmail(
                "msg-aprovado",
                "Neoenergia Coelba - Solicitação 4410023",
                "Solicitação nº 4410023\nInformamos que a solicitação foi DEFERIDA.",
                recebidoEm));

        assertThat(resultado).isEqualTo(ResultadoProcessamento.APLICADO);

        Projeto atualizado = projetoRepository.findById(projeto.getId()).orElseThrow();
        assertThat(atualizado.getStatus()).isEqualTo(StatusProjeto.APROVADO);
        // A data é a do e-mail, no fuso da Bahia, e não a da execução do job: um e-mail das 20h30
        // de 16/09 em Salvador chega como 23h30 UTC e viraria 17/09 se o fuso fosse ignorado —
        // a métrica de tempo até aprovação nasceria com um dia a mais.
        assertThat(atualizado.getDataAprovacao())
                .isEqualTo(recebidoEm.atZone(FUSO).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 9, 16));

        List<HistoricoStatus> historico = historicoStatusRepository
                .findByEntidadeTipoAndEntidadeId(EntidadeTipo.PROJETO, projeto.getId());
        assertThat(historico).hasSize(1);
        assertThat(historico.get(0).getStatusNovo()).isEqualTo("APROVADO");
        assertThat(historico.get(0).getUsuarioId()).isEqualTo(idDaContaDeIntegracao());

        EmailCoelba registro = umRegistro();
        assertThat(registro.getResultado()).isEqualTo(ResultadoProcessamento.APLICADO);
        assertThat(registro.getProjetoId()).isEqualTo(projeto.getId());
        assertThat(registro.getNumeroSolicitacao()).isEqualTo("4410023");
        assertThat(registro.getAssunto()).contains("4410023");
    }

    @Test
    void emailDeReprovacaoGravaOMotivoLidoDoTexto() {
        Projeto projeto = projetoEncaminhado("4410099");

        ResultadoProcessamento resultado = retornoCoelbaService.processar(new MensagemGmail(
                "msg-reprovado",
                "Solicitação 4410099",
                "Solicitação 4410099\nA solicitação foi reprovada por ausência da ART.",
                Instant.now()));

        assertThat(resultado).isEqualTo(ResultadoProcessamento.APLICADO);

        Projeto atualizado = projetoRepository.findById(projeto.getId()).orElseThrow();
        assertThat(atualizado.getStatus()).isEqualTo(StatusProjeto.REPROVADO);
        assertThat(atualizado.getMotivoReprova()).contains("ausência da ART");
    }

    @Test
    void numeroQueNaoCasaComProjetoNenhumNaoMudaNadaEFicaRegistrado() {
        Projeto projeto = projetoEncaminhado("4410023");

        ResultadoProcessamento resultado = retornoCoelbaService.processar(new MensagemGmail(
                "msg-sem-projeto",
                "Solicitação 9999999",
                "Solicitação nº 9999999 deferida.",
                Instant.now()));

        assertThat(resultado).isEqualTo(ResultadoProcessamento.SEM_CORRESPONDENCIA);
        assertThat(projetoRepository.findById(projeto.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProjeto.ENCAMINHADO);
        // O registro diz o que foi lido, e não só que não casou: "leu uma aprovação de um
        // projeto que não está aqui" e "leu qualquer coisa" pedem investigações diferentes.
        EmailCoelba registro = umRegistro();
        assertThat(registro.getDetalhe()).contains("9999999").contains("APROVADO");
        assertThat(registro.getNumeroSolicitacao()).isEqualTo("9999999");
    }

    /**
     * O caso do teste em homologação de 01/10/2026: a solicitação já aprovada na Coelba é lançada
     * no SolarSync depois de o e-mail chegar. A primeira leitura não acha projeto; sem a releitura,
     * o e-mail contava como processado e o projeto ficava em ENCAMINHADO com a aprovação na caixa.
     */
    @Test
    void emailLidoAntesDeOProjetoExistirEAplicadoQuandoEleAparece() {
        MensagemGmail mensagem = new MensagemGmail("msg-adiantada", "Solicitação 2609290073",
                "Solicitação nº 2609290073 deferida.", Instant.now());

        assertThat(retornoCoelbaService.processar(mensagem))
                .isEqualTo(ResultadoProcessamento.SEM_CORRESPONDENCIA);
        assertThat(emailCoelbaRepository.idsJaProcessados(List.of("msg-adiantada"))).isEmpty();

        Projeto projeto = projetoEncaminhado("2609290073");

        assertThat(retornoCoelbaService.processar(mensagem))
                .isEqualTo(ResultadoProcessamento.APLICADO);
        assertThat(projetoRepository.findById(projeto.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProjeto.APROVADO);

        // O mesmo registro, atualizado — e agora definitivo.
        EmailCoelba registro = umRegistro();
        assertThat(registro.getResultado()).isEqualTo(ResultadoProcessamento.APLICADO);
        assertThat(registro.getProjetoId()).isEqualTo(projeto.getId());
        assertThat(emailCoelbaRepository.idsJaProcessados(List.of("msg-adiantada")))
                .containsExactly("msg-adiantada");
    }

    /** Sem número no texto não há projeto futuro que case: reler seria só custo. */
    @Test
    void semCorrespondenciaSemNumeroContinuaDefinitivo() {
        retornoCoelbaService.processar(new MensagemGmail("msg-sem-numero", "Aviso",
                "Sua solicitação foi deferida.", Instant.now()));

        EmailCoelba registro = umRegistro();
        assertThat(registro.getResultado()).isEqualTo(ResultadoProcessamento.SEM_CORRESPONDENCIA);
        assertThat(emailCoelbaRepository.idsJaProcessados(List.of("msg-sem-numero")))
                .containsExactly("msg-sem-numero");
    }

    /**
     * Releitura de um e-mail já aplicado não pode sobrescrever o registro: viraria
     * SEM_ALTERACAO, e a trilha perderia que foi este e-mail que mudou o projeto.
     */
    @Test
    void releituraDeEmailAplicadoNaoApagaOAplicadoDaTrilha() {
        projetoEncaminhado("4410077");
        MensagemGmail mensagem = new MensagemGmail("msg-aplicada", "Solicitação 4410077",
                "Solicitação nº 4410077 deferida.", Instant.now());

        retornoCoelbaService.processar(mensagem);
        retornoCoelbaService.processar(mensagem);

        assertThat(umRegistro().getResultado()).isEqualTo(ResultadoProcessamento.APLICADO);
    }

    /**
     * O caso que justifica {@code RetornoCoelbaService} não ser transacional. A máquina de
     * estados barra aprovar um projeto que nunca foi encaminhado — e, se a aplicação e o registro
     * estivessem na mesma transação, a exceção a marcaria como "somente rollback" e o registro do
     * que aconteceu (justamente o que se quer guardar) iria embora junto.
     */
    @Test
    void transicaoBarradaPelaMaquinaDeEstadosNaoMudaNadaMasFicaRegistrada() {
        Projeto projeto = projeto("4410044", StatusProjeto.RECEBIDO);

        ResultadoProcessamento resultado = retornoCoelbaService.processar(new MensagemGmail(
                "msg-transicao",
                "Solicitação 4410044",
                "Solicitação nº 4410044 deferida.",
                Instant.now()));

        assertThat(resultado).isEqualTo(ResultadoProcessamento.TRANSICAO_INVALIDA);

        Projeto intacto = projetoRepository.findById(projeto.getId()).orElseThrow();
        assertThat(intacto.getStatus()).isEqualTo(StatusProjeto.RECEBIDO);
        // A transação do service foi revertida inteira: nem a data de aprovação sobrou.
        assertThat(intacto.getDataAprovacao()).isNull();
        assertThat(historicoStatusRepository.findByEntidadeTipoAndEntidadeId(
                EntidadeTipo.PROJETO, projeto.getId())).isEmpty();

        EmailCoelba registro = umRegistro();
        assertThat(registro.getResultado()).isEqualTo(ResultadoProcessamento.TRANSICAO_INVALIDA);
        assertThat(registro.getProjetoId()).isEqualTo(projeto.getId());
    }

    /**
     * Releitura do mesmo e-mail não pode gerar segunda linha de histórico — seria um duplo
     * registro distorcendo as contagens do dashboard. O job já descarta a mensagem conhecida
     * antes de buscá-la; isto prova a segunda barreira, para o caso de ela escapar.
     */
    @Test
    void reprocessarAMesmaMensagemNaoDuplicaHistoricoNemRegistro() {
        Projeto projeto = projetoEncaminhado("4410023");
        MensagemGmail mensagem = new MensagemGmail("msg-repetida", "Solicitação 4410023",
                "Solicitação nº 4410023 deferida.", Instant.now());

        assertThat(retornoCoelbaService.processar(mensagem))
                .isEqualTo(ResultadoProcessamento.APLICADO);
        assertThat(retornoCoelbaService.processar(mensagem))
                .isEqualTo(ResultadoProcessamento.SEM_ALTERACAO);

        assertThat(historicoStatusRepository.findByEntidadeTipoAndEntidadeId(
                EntidadeTipo.PROJETO, projeto.getId())).hasSize(1);
        // O único em mensagem_id impede a segunda linha; a exceção é engolida de propósito.
        assertThat(emailCoelbaRepository.findAll()).hasSize(1);
    }

    @Test
    void doisProjetosComOMesmoNumeroNaoDeixamAIntegracaoEscolher() {
        Projeto primeiro = projetoEncaminhado("4410055");
        Projeto segundo = projetoEncaminhado("4410055");

        ResultadoProcessamento resultado = retornoCoelbaService.processar(new MensagemGmail(
                "msg-ambigua", "Solicitação 4410055",
                "Solicitação nº 4410055 deferida.", Instant.now()));

        assertThat(resultado).isEqualTo(ResultadoProcessamento.AMBIGUO);
        assertThat(projetoRepository.findById(primeiro.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProjeto.ENCAMINHADO);
        assertThat(projetoRepository.findById(segundo.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProjeto.ENCAMINHADO);
    }

    @Test
    void emailQueOParserNaoEntendeFicaVisivelEmVezDeDesaparecer() {
        projetoEncaminhado("4410023");

        ResultadoProcessamento resultado = retornoCoelbaService.processar(new MensagemGmail(
                "msg-desconhecida", "Aviso de manutenção",
                "Prezado cliente, informamos a interrupção programada de energia.",
                Instant.now()));

        assertThat(resultado).isEqualTo(ResultadoProcessamento.NAO_RECONHECIDO);
        assertThat(umRegistro().getAssunto()).isEqualTo("Aviso de manutenção");
    }

    @Test
    void acompanhamentoEmAnaliseNaoMudaOProjeto() {
        Projeto projeto = projetoEncaminhado("4410050");

        ResultadoProcessamento resultado = retornoCoelbaService.processar(new MensagemGmail(
                "msg-analise", "Solicitação 4410050",
                "Solicitação nº 4410050 encontra-se em análise.", Instant.now()));

        assertThat(resultado).isEqualTo(ResultadoProcessamento.SEM_ALTERACAO);
        assertThat(projetoRepository.findById(projeto.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProjeto.ENCAMINHADO);
        assertThat(historicoStatusRepository.findByEntidadeTipoAndEntidadeId(
                EntidadeTipo.PROJETO, projeto.getId())).isEmpty();
    }

    private Projeto projetoEncaminhado(String numeroSolicitacao) {
        return projeto(numeroSolicitacao, StatusProjeto.ENCAMINHADO);
    }

    /**
     * Monta o projeto direto no repositório: o teste é sobre o e-mail ser aplicado, não sobre
     * como o projeto chegou ao status — e passar pelo fluxo completo exigiria pendência, consulta
     * de débito e encaminhamento só para preparar o cenário.
     */
    private Projeto projeto(String numeroSolicitacao, StatusProjeto status) {
        Cliente cliente = clienteRepository.save(Cliente.builder()
                .nome("Cliente " + numeroSolicitacao)
                .build());
        return projetoRepository.save(Projeto.builder()
                .cliente(cliente)
                .tipoProjeto(TipoProjeto.PROJETO_INICIAL)
                .status(status)
                .numeroSolicitacao(numeroSolicitacao)
                .dataRecebimento(LocalDate.now().minusDays(10))
                .dataEncaminhado(status == StatusProjeto.ENCAMINHADO
                        ? LocalDate.now().minusDays(5)
                        : null)
                .build());
    }

    private EmailCoelba umRegistro() {
        List<EmailCoelba> registros = emailCoelbaRepository.findAll();
        assertThat(registros).hasSize(1);
        return registros.get(0);
    }

    private Long idDaContaDeIntegracao() {
        return usuarioRepository.findByEmail(UsuarioIntegracao.EMAIL)
                .map(Usuario::getId)
                .orElseThrow();
    }
}
