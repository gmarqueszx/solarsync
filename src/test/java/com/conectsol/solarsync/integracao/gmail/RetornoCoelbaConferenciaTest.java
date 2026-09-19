package com.conectsol.solarsync.integracao.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.conectsol.solarsync.TestcontainersConfiguration;
import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.conectsol.solarsync.projeto.StatusProjeto;
import com.conectsol.solarsync.projeto.TipoProjeto;

/**
 * O ensaio antes da estreia: com {@code solarsync.gmail.somente-conferencia=true}, a leitura do
 * e-mail da Coelba acontece inteira e <b>nenhum</b> status de projeto muda.
 * <p>
 * Existe porque a redação real do e-mail da Coelba nunca foi observada (seção 9 do CLAUDE.md):
 * as listas de termos são o provável, e um palpite errado reprovaria um projeto de verdade,
 * sujando o {@code historico_status} de onde saem todas as métricas do dashboard.
 * <p>
 * Não estende {@code AbstractIntegrationTest} de propósito: um {@code @SpringBootTest} na
 * subclasse substitui o da superclasse em vez de somar, então as propriedades de lá estão
 * repetidas aqui — inclusive as três que impedem a configuração local de desenvolvimento de
 * vazar para os testes.
 */
@SpringBootTest(properties = {
        "solarsync.dados-de-exemplo=false",
        "solarsync.nectar.ativo=false",
        "solarsync.gmail.ativo=false",
        "solarsync.gmail.somente-conferencia=true" })
@Import(TestcontainersConfiguration.class)
class RetornoCoelbaConferenciaTest {

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

    @AfterEach
    void limpar() {
        historicoStatusRepository.deleteAll();
        emailCoelbaRepository.deleteAll();
        projetoRepository.deleteAll();
        clienteRepository.deleteAll();
    }

    @Test
    void naoMudaOProjetoMasRegistraAMudancaQueTeriaFeito() {
        Projeto projeto = projetoEncaminhado("4410023");

        ResultadoProcessamento resultado = retornoCoelbaService.processar(new MensagemGmail(
                "msg-conferencia", "Solicitação 4410023",
                "Solicitação nº 4410023\nInformamos que a solicitação foi DEFERIDA.",
                Instant.now()));

        assertThat(resultado).isEqualTo(ResultadoProcessamento.CONFERENCIA);

        assertThat(projetoRepository.findById(projeto.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProjeto.ENCAMINHADO);
        assertThat(historicoStatusRepository.findByEntidadeTipoAndEntidadeId(
                EntidadeTipo.PROJETO, projeto.getId())).isEmpty();

        EmailCoelba registro = umRegistro();
        assertThat(registro.getResultado()).isEqualTo(ResultadoProcessamento.CONFERENCIA);
        assertThat(registro.getProjetoId()).isEqualTo(projeto.getId());
        assertThat(registro.getNumeroSolicitacao()).isEqualTo("4410023");
        assertThat(registro.getDetalhe())
                .contains("teria mudado de ENCAMINHADO para APROVADO");
    }

    /**
     * A garantia de que o ensaio não engole o e-mail de verdade: desligado o modo conferência, a
     * mensagem precisa voltar a ser lida para enfim ser aplicada. Se {@code CONFERENCIA} contasse
     * como processada, o job a descartaria e o projeto nunca seria aprovado.
     */
    @Test
    void emailConferidoNaoContaComoProcessado() {
        projetoEncaminhado("4410077");

        retornoCoelbaService.processar(new MensagemGmail(
                "msg-volta", "Solicitação 4410077",
                "Solicitação nº 4410077 deferida.", Instant.now()));

        assertThat(emailCoelbaRepository.findAll()).hasSize(1);
        assertThat(emailCoelbaRepository.idsJaProcessados(List.of("msg-volta"))).isEmpty();
    }

    /**
     * O ciclo de ajustar os termos e conferir de novo: no modo conferência o job relê as mesmas
     * mensagens a cada execução, e o registro é atualizado em vez de duplicado — senão o único em
     * {@code mensagem_id} barraria a segunda gravação e a tabela guardaria só o primeiro palpite.
     */
    @Test
    void reconferirAMesmaMensagemAtualizaORegistroEmVezDeDuplicar() {
        Projeto projeto = projetoEncaminhado("4410088");
        MensagemGmail aprovacao = new MensagemGmail("msg-reconferida", "Solicitação 4410088",
                "Solicitação nº 4410088 deferida.", Instant.now());
        MensagemGmail reprovacao = new MensagemGmail("msg-reconferida", "Solicitação 4410088",
                "Solicitação nº 4410088 indeferida por ausência da ART.", Instant.now());

        retornoCoelbaService.processar(aprovacao);
        retornoCoelbaService.processar(reprovacao);

        assertThat(emailCoelbaRepository.findAll()).hasSize(1);
        assertThat(umRegistro().getDetalhe())
                .contains("teria mudado de ENCAMINHADO para REPROVADO")
                .contains("ausência da ART");
        assertThat(projetoRepository.findById(projeto.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProjeto.ENCAMINHADO);
    }

    /**
     * Só o passo de aplicar desvia. O e-mail que o parser não entende continua caindo como
     * {@code NAO_RECONHECIDO} — é justamente esse o diagnóstico que se foi buscar no ensaio.
     */
    @Test
    void emailNaoReconhecidoContinuaVisivelComoNaoReconhecido() {
        projetoEncaminhado("4410023");

        ResultadoProcessamento resultado = retornoCoelbaService.processar(new MensagemGmail(
                "msg-estranha", "Aviso de manutenção",
                "Prezado cliente, informamos a interrupção programada de energia.",
                Instant.now()));

        assertThat(resultado).isEqualTo(ResultadoProcessamento.NAO_RECONHECIDO);
        assertThat(umRegistro().getAssunto()).isEqualTo("Aviso de manutenção");
    }

    private Projeto projetoEncaminhado(String numeroSolicitacao) {
        Cliente cliente = clienteRepository.save(Cliente.builder()
                .nome("Cliente " + numeroSolicitacao)
                .build());
        return projetoRepository.save(Projeto.builder()
                .cliente(cliente)
                .tipoProjeto(TipoProjeto.PROJETO_INICIAL)
                .status(StatusProjeto.ENCAMINHADO)
                .numeroSolicitacao(numeroSolicitacao)
                .dataRecebimento(LocalDate.now().minusDays(10))
                .dataEncaminhado(LocalDate.now().minusDays(5))
                .build());
    }

    private EmailCoelba umRegistro() {
        List<EmailCoelba> registros = emailCoelbaRepository.findAll();
        assertThat(registros).hasSize(1);
        return registros.get(0);
    }
}
