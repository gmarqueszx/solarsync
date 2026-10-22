package com.conectsol.solarsync.projeto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.common.RepositoryTest;
import com.conectsol.solarsync.historico.HistoricoStatus;
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.vistoria.StatusVistoria;
import com.conectsol.solarsync.vistoria.Vistoria;
import com.conectsol.solarsync.vistoria.VistoriaRepository;

/**
 * A consulta que recorta a busca no Gmail aos projetos que ainda esperam notícia da Coelba
 * (pedido do usuário em 19/09/2026). É o tipo de regra cujo erro <b>não</b> aparece em lugar
 * nenhum: se ela devolver de menos, o e-mail daquele projeto simplesmente nunca é buscado, e o
 * projeto fica parado sem nada em {@code email_coelba} para denunciar.
 */
@RepositoryTest
class NumerosAguardandoRetornoTest {

    @Autowired
    private ProjetoRepository projetoRepository;

    @Autowired
    private VistoriaRepository vistoriaRepository;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private HistoricoStatusRepository historicoStatusRepository;

    @Test
    void trazOsEncaminhadosEOsReencaminhados() {
        projeto("1000000001", StatusProjeto.ENCAMINHADO);
        projeto("1000000002", StatusProjeto.REENCAMINHADO);

        assertThat(projetoRepository.numerosAguardandoRetornoDaCoelba())
                .containsExactlyInAnyOrder("1000000001", "1000000002");
    }

    @Test
    void naoTrazQuemNaoFoiAEnviadoNemQuemJaEncerrou() {
        projeto("1000000003", StatusProjeto.RECEBIDO);
        projeto("1000000004", StatusProjeto.AGUARDANDO_ENVIO);
        projeto("1000000005", StatusProjeto.REPROVADO);

        assertThat(projetoRepository.numerosAguardandoRetornoDaCoelba()).isEmpty();
    }

    /**
     * ⚠️ O caso que mais importa. O e-mail de vistoria ("Realizando vistoria e Conexão", "Ponto
     * de Conexão Aprovado") chega quando o projeto já está APROVADO — fora de ENCAMINHADO.
     * Recortar a busca só pelos encaminhados desligaria a automação da etapa 4 em silêncio.
     */
    @Test
    void trazOProjetoAprovadoQueAindaTemVistoriaEmAberto() {
        Projeto aprovado = projeto("1000000006", StatusProjeto.APROVADO);
        vistoriaRepository.save(Vistoria.builder()
                .projeto(aprovado)
                .dataSolicitacao(LocalDate.now().minusDays(3))
                .status(StatusVistoria.SOLICITADA)
                .build());

        assertThat(projetoRepository.numerosAguardandoRetornoDaCoelba())
                .containsExactly("1000000006");
    }

    /**
     * Vistoria aprovada encerra o ciclo: dali não vem mais nada do portal que mude algo aqui. É
     * o que impede a lista de crescer para sempre — e, com ela, a consulta ao Gmail.
     */
    @Test
    void naoTrazOProjetoCujaVistoriaJaFoiAprovada() {
        Projeto concluido = projeto("1000000007", StatusProjeto.APROVADO);
        vistoriaRepository.save(Vistoria.builder()
                .projeto(concluido)
                .dataSolicitacao(LocalDate.now().minusDays(10))
                .dataResultado(LocalDate.now().minusDays(1))
                .status(StatusVistoria.APROVADA)
                .build());

        assertThat(projetoRepository.numerosAguardandoRetornoDaCoelba()).isEmpty();
    }

    /** Vistoria reprovada volta para a fila: haverá nova solicitação e novo retorno. */
    @Test
    void trazOProjetoComVistoriaReprovada() {
        Projeto reprovado = projeto("1000000008", StatusProjeto.APROVADO);
        vistoriaRepository.save(Vistoria.builder()
                .projeto(reprovado)
                .dataSolicitacao(LocalDate.now().minusDays(8))
                .dataResultado(LocalDate.now().minusDays(2))
                .status(StatusVistoria.REPROVADA)
                .build());

        assertThat(projetoRepository.numerosAguardandoRetornoDaCoelba())
                .containsExactly("1000000008");
    }

    /**
     * Projeto sem número não entra: não há o que procurar no e-mail. É o caso do projeto
     * encaminhado antes de a obrigatoriedade do número existir, e o da importação da planilha.
     */
    @Test
    void ignoraProjetoSemNumeroDeSolicitacao() {
        projeto(null, StatusProjeto.ENCAMINHADO);

        assertThat(projetoRepository.numerosAguardandoRetornoDaCoelba()).isEmpty();
    }

    /**
     * O mesmo número em dois projetos aparece uma vez só: a consulta do Gmail não ganha nada com
     * a repetição, e ela conta para o limite de tamanho do lote.
     */
    @Test
    void naoRepeteONumeroQuandoDoisProjetosOCompartilham() {
        projeto("1000000009", StatusProjeto.ENCAMINHADO);
        projeto("1000000009", StatusProjeto.REENCAMINHADO);

        assertThat(projetoRepository.numerosAguardandoRetornoDaCoelba())
                .containsExactly("1000000009");
    }

    /**
     * A busca sem limite de data (22/10/2026): o projeto encaminhado há pouco entra, mesmo com
     * {@code data_encaminhado} retroativa — o que conta é quando ele passou a esperar aqui.
     */
    @Test
    void encaminhadoHaPoucoEntraNaBuscaSemLimiteDeData() {
        Projeto recente = projeto("2610021352", StatusProjeto.ENCAMINHADO);
        historico(recente, "RECEBIDO", "ENCAMINHADO", Instant.now().minus(Duration.ofHours(1)));

        assertThat(projetoRepository.numerosEncaminhadosDesde(
                Instant.now().minus(Duration.ofHours(24))))
                .containsExactly("2610021352");
    }

    @Test
    void encaminhadoHaMaisTempoQueAJanelaNaoEntra() {
        Projeto antigo = projeto("1000000010", StatusProjeto.ENCAMINHADO);
        historico(antigo, "RECEBIDO", "ENCAMINHADO", Instant.now().minus(Duration.ofDays(3)));

        assertThat(projetoRepository.numerosEncaminhadosDesde(
                Instant.now().minus(Duration.ofHours(24))))
                .isEmpty();
    }

    /** Retorno já aplicado: o projeto saiu de ENCAMINHADO, e não há mais o que buscar. */
    @Test
    void quemJaSaiuDeEncaminhadoNaoEntra() {
        Projeto aprovado = projeto("1000000011", StatusProjeto.APROVADO);
        historico(aprovado, "RECEBIDO", "ENCAMINHADO", Instant.now().minus(Duration.ofHours(2)));
        historico(aprovado, "ENCAMINHADO", "APROVADO", Instant.now().minus(Duration.ofHours(1)));

        assertThat(projetoRepository.numerosEncaminhadosDesde(
                Instant.now().minus(Duration.ofHours(24))))
                .isEmpty();
    }

    /**
     * ⚠️ O reenvio fica de fora de propósito: com o número do ciclo anterior, a busca sem data
     * traria um cancelamento duplicado daquele ciclo, que reprovaria o envio novo.
     */
    @Test
    void reenvioNaoEntraNaBuscaSemLimiteDeData() {
        Projeto reenviado = projeto("1000000012", StatusProjeto.REENCAMINHADO);
        historico(reenviado, "REPROVADO", "REENCAMINHADO", Instant.now().minus(Duration.ofHours(1)));

        assertThat(projetoRepository.numerosEncaminhadosDesde(
                Instant.now().minus(Duration.ofHours(24))))
                .isEmpty();
    }

    private void historico(Projeto projeto, String anterior, String novo, Instant quando) {
        historicoStatusRepository.save(HistoricoStatus.builder()
                .entidadeTipo(EntidadeTipo.PROJETO)
                .entidadeId(projeto.getId())
                .statusAnterior(anterior)
                .statusNovo(novo)
                .ocorridoEm(quando)
                .build());
    }

    private Projeto projeto(String numeroSolicitacao, StatusProjeto status) {
        Cliente cliente = clienteRepository.save(Cliente.builder()
                .nome("Cliente " + status + " " + System.nanoTime())
                .build());
        return projetoRepository.save(Projeto.builder()
                .cliente(cliente)
                .tipoProjeto(TipoProjeto.PROJETO_INICIAL)
                .status(status)
                .numeroSolicitacao(numeroSolicitacao)
                .dataRecebimento(LocalDate.now().minusDays(20))
                .build());
    }
}
