package com.conectsol.solarsync.integracao.nectar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.conectsol.solarsync.TestcontainersConfiguration;
import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.cliente.dto.ClienteRequest;

/**
 * A sincronização de saída com o CRM, com o cliente HTTP substituído por um dublê.
 * <p>
 * É o único teste do projeto que sobe uma integração <b>ligada</b> — e existe por causa do buraco
 * registrado na seção 11 do CLAUDE.md: com {@code ativo=false}, que é o padrão e o que a suíte
 * força, os beans condicionais não existem e nenhum teste os toca. Foi assim que a falta do bean
 * {@code RestClient.Builder} passou por 174 testes verdes.
 * <p>
 * Não estende {@code AbstractIntegrationTest} justamente porque aquela classe força
 * {@code solarsync.nectar.ativo=false}. As duas propriedades ligadas aqui criam o listener, o
 * serviço e — inevitavelmente — o job de entrada, que é agendado para 30 s depois do boot. A
 * {@code base-url} aponta para uma porta morta para o caso de o teste demorar tanto: o job trata
 * a falha de rede e segue, então o pior caso é uma linha de ERROR no log, nunca uma chamada ao
 * CRM de verdade.
 */
@SpringBootTest(properties = {
        "solarsync.dados-de-exemplo=false",
        "solarsync.gmail.ativo=false",
        "solarsync.gmail.somente-conferencia=false",
        "solarsync.nectar.ativo=true",
        "solarsync.nectar.saida.ativo=true",
        "solarsync.nectar.saida.somente-conferencia=false",
        "solarsync.nectar.base-url=http://localhost:1",
        "solarsync.nectar.token=token-de-teste" })
@Import(TestcontainersConfiguration.class)
class NectarEtapaSincronizacaoTest {

    private static final String OPORTUNIDADE = "29878256";

    /** "PROJETO PARA FAZER" no funil "6- Projetos". */
    private static final long ETAPA_PARA_FAZER = 288715L;
    /** "PROJETO APROVADO - AGUARDANDO INSTALAÇÃO", o destino do projeto normal aprovado. */
    private static final long ETAPA_APROVADO_NORMAL = 288719L;
    /** "PROJETO APROVADO SEM PAGAMENTO (BANCO...)", o destino do projeto Banco aprovado. */
    private static final long ETAPA_APROVADO_BANCO = 288686L;
    private static final long FUNIL_PROJETOS = 58570L;

    @MockitoBean
    private NectarClient nectarClient;

    @Autowired
    private NectarEtapaService etapaService;

    @Autowired
    private NectarEtapaSincronizacaoRepository sincronizacaoRepository;

    @Autowired
    private com.conectsol.solarsync.historico.HistoricoStatusRepository historicoStatusRepository;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private com.conectsol.solarsync.cliente.ClienteService clienteService;

    @Autowired
    private com.conectsol.solarsync.projeto.ProjetoRepository projetoRepository;

    @AfterEach
    void limpar() {
        sincronizacaoRepository.deleteAll();
        historicoStatusRepository.deleteAll();
        projetoRepository.deleteAll();
        clienteRepository.deleteAll();
    }

    @Test
    void moveOClienteParaAEtapaCorrespondenteERegistraAOperacao() {
        Cliente cliente = clienteDoCrm(false);
        estaNaEtapa(288714L);

        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_PARA_FAZER);

        verify(nectarClient).moverParaEtapa(OPORTUNIDADE, FUNIL_PROJETOS, ETAPA_PARA_FAZER);

        NectarEtapaSincronizacao trilha = ultima();
        assertThat(trilha.getResultado()).isEqualTo(ResultadoSincronizacao.APLICADO);
        assertThat(trilha.getEtapaId()).isEqualTo(ETAPA_PARA_FAZER);
        assertThat(trilha.getOportunidadeId()).isEqualTo(OPORTUNIDADE);
        assertThat(trilha.isBanco()).isFalse();
    }

    /**
     * O projeto Banco muda de destino só na aprovação — é a única linha em que os dois
     * mapeamentos diferem, e é a que o comercial usa para saber que ainda falta o financiamento.
     */
    @Test
    void oProjetoBancoAprovadoVaiParaAEtapaDeAprovadoSemPagamento() {
        Cliente cliente = clienteDoCrm(true);
        estaNaEtapa(288716L);

        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_APROVADO);

        verify(nectarClient).moverParaEtapa(OPORTUNIDADE, FUNIL_PROJETOS, ETAPA_APROVADO_BANCO);
        assertThat(ultima().isBanco()).isTrue();
    }

    @Test
    void oProjetoNormalAprovadoVaiParaAEtapaDeAguardandoInstalacao() {
        Cliente cliente = clienteDoCrm(false);
        estaNaEtapa(288716L);

        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_APROVADO);

        verify(nectarClient).moverParaEtapa(OPORTUNIDADE, FUNIL_PROJETOS, ETAPA_APROVADO_NORMAL);
    }

    /**
     * Idempotência contra o próprio CRM: alguém pode ter arrastado o card no painel, ou o banco
     * daqui pode ter sido recriado. É a camada que vale de verdade — a trilha local só evita a
     * viagem.
     */
    @Test
    void clienteJaNaEtapaCertaNaoEhMovidoDeNovo() {
        Cliente cliente = clienteDoCrm(false);
        estaNaEtapa(ETAPA_PARA_FAZER);

        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_PARA_FAZER);

        verify(nectarClient, never()).moverParaEtapa(anyString(), anyLong(), anyLong());
        // Registrado, e não silencioso: sem a linha, "não fez nada" e "não rodou" se confundem.
        assertThat(ultima().getResultado()).isEqualTo(ResultadoSincronizacao.JA_NA_ETAPA);
    }

    /**
     * Repetir a mesma transição não gera nem a consulta ao CRM: a trilha local responde antes.
     * É o que impede uma sequência de eventos do mesmo instante do fluxo de virar uma rajada de
     * requisições.
     */
    @Test
    void repetirAMesmaSincronizacaoNaoChamaOCrmDeNovo() {
        Cliente cliente = clienteDoCrm(false);
        estaNaEtapa(288714L);

        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_PARA_FAZER);
        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_PARA_FAZER);

        verify(nectarClient).moverParaEtapa(OPORTUNIDADE, FUNIL_PROJETOS, ETAPA_PARA_FAZER);
        verify(nectarClient).oportunidade(OPORTUNIDADE);
        assertThat(sincronizacaoRepository.findAll()).hasSize(1);
    }

    /**
     * Voltar para uma etapa anterior é movimento legítimo — projeto aprovado que a Coelba revisa
     * e reprova. A idempotência olha só a <b>última</b> linha: uma busca por "já estivemos nesta
     * etapa alguma vez" deixaria o cliente preso na etapa mais recente para sempre.
     */
    @Test
    void voltarParaUmaEtapaAnteriorVoltaAChamarOCrm() {
        Cliente cliente = clienteDoCrm(false);
        estaNaEtapa(288714L);

        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_APROVADO);
        estaNaEtapa(ETAPA_APROVADO_NORMAL);
        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_REPROVADO);
        estaNaEtapa(288717L);
        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_APROVADO);

        verify(nectarClient, org.mockito.Mockito.times(2))
                .moverParaEtapa(OPORTUNIDADE, FUNIL_PROJETOS, ETAPA_APROVADO_NORMAL);
    }

    /**
     * A falha do CRM não desfaz nada aqui dentro: o status do projeto já está commitado quando
     * esta chamada acontece. O que ela deixa é a linha de ERRO — a única pista de que o CRM ficou
     * para trás, e a fila do reprocessamento.
     */
    @Test
    void falhaDaApiDoNectarNaoQuebraNadaEFicaRegistrada() {
        Cliente cliente = clienteDoCrm(false);
        estaNaEtapa(288714L);
        doThrow(new IllegalStateException("502 Bad Gateway"))
                .when(nectarClient).moverParaEtapa(anyString(), anyLong(), anyLong());

        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_PARA_FAZER);

        NectarEtapaSincronizacao trilha = ultima();
        assertThat(trilha.getResultado()).isEqualTo(ResultadoSincronizacao.ERRO);
        assertThat(trilha.getDetalhe()).contains("502 Bad Gateway");
    }

    /** A falha entra na fila do reprocessamento — e some dela assim que der certo. */
    @Test
    void aFalhaEntraNaFilaDeReprocessamentoESaiQuandoDaCerto() {
        Cliente cliente = clienteDoCrm(false);
        estaNaEtapa(288714L);
        doThrow(new IllegalStateException("timeout"))
                .when(nectarClient).moverParaEtapa(anyString(), anyLong(), anyLong());

        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_PARA_FAZER);
        assertThat(pendentes()).hasSize(1);

        org.mockito.Mockito.reset(nectarClient);
        estaNaEtapa(288714L);
        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_PARA_FAZER);

        assertThat(pendentes())
                .as("a falha deixa de ser a última palavra sobre o cliente")
                .isEmpty();
    }

    /**
     * Metade da base entra pela tela, não pelo CRM. Não é defeito: é o cliente que o comercial
     * nunca cadastrou como oportunidade, e não há o que mover.
     */
    @Test
    void clienteSemOportunidadeNoCrmNaoEhSincronizado() {
        Cliente manual = clienteRepository.save(Cliente.builder().nome("Cadastro Manual").build());

        etapaService.sincronizar(manual.getId(), EtapaDoFluxo.PROJETO_PARA_FAZER);

        verify(nectarClient, never()).oportunidade(anyString());
        assertThat(ultima().getResultado()).isEqualTo(ResultadoSincronizacao.SEM_OPORTUNIDADE);
    }

    /**
     * Situação sem etapa configurada vira registro, não exceção — e nem chega a consultar o CRM.
     * O sintoma que isso evita é um cliente parado na etapa errada sem nada no log.
     */
    @Test
    void situacaoSemEtapaConfiguradaEhRegistradaComoSemMapeamento() {
        Cliente cliente = clienteDoCrm(false);

        // Simula configuração incompleta tirando a etapa do mapa por um serviço com propriedades
        // próprias — o mesmo caminho, sem a entrada.
        NectarEtapaService semMapa = new NectarEtapaService(clienteRepository,
                sincronizacaoRepository, nectarClient,
                new NectarSaidaProperties(true, false, 0, 0,
                        Map.of(EtapaDoFluxo.PROJETO_APROVADO, 1L), Map.of(), null, 0));

        semMapa.sincronizar(cliente.getId(), EtapaDoFluxo.PROJETO_PARA_FAZER);

        verify(nectarClient, never()).oportunidade(anyString());
        assertThat(ultima().getResultado()).isEqualTo(ResultadoSincronizacao.SEM_MAPEAMENTO);
    }

    /** As duas etapas de vistoria vivem no outro funil; o funil certo vai junto na chamada. */
    @Test
    void aVistoriaUsaOFunilDeInstalacao() {
        Cliente cliente = clienteDoCrm(false);
        estaNaEtapa(288719L);

        etapaService.sincronizar(cliente.getId(), EtapaDoFluxo.VISTORIA_SOLICITADA);

        verify(nectarClient).moverParaEtapa(OPORTUNIDADE, 58575L, 288728L);
    }

    /**
     * A fiação do listener, e não só a do serviço: uma transição de verdade do domínio tem de
     * chegar até o CRM sozinha.
     * <p>
     * É o que o requisito quer dizer com "a sincronização não deve ser apenas visual" — e o que
     * um teste do serviço isolado não prova, porque ele pula justamente a parte que pode quebrar
     * em silêncio (o {@code @TransactionalEventListener} deixar de casar com o evento).
     * <p>
     * Sem {@code @Transactional} de propósito: o listener é {@code AFTER_COMMIT}, então só
     * dispara quando a transição realmente commita.
     */
    @Test
    void umaTransicaoDeVerdadeDoDominioChegaAoCrmSozinha() {
        estaNaEtapa(288714L);
        Long clienteId = clienteService
                .criarDoNectar(new ClienteRequest("Cliente do CRM", "Salvador", null, null, null,
                        null, false, false), OPORTUNIDADE)
                .orElseThrow()
                .id();

        // A triagem sem pendência faz o projeto nascer em RECEBIDO — e é essa transição que o
        // CRM precisa refletir como "PROJETO PARA FAZER".
        clienteService.marcarSemPendencia(clienteId, null);

        verify(nectarClient).moverParaEtapa(OPORTUNIDADE, FUNIL_PROJETOS, ETAPA_PARA_FAZER);
        assertThat(ultima().getResultado()).isEqualTo(ResultadoSincronizacao.APLICADO);
    }

    private Cliente clienteDoCrm(boolean banco) {
        return clienteRepository.save(Cliente.builder()
                .nome("Cliente do CRM")
                .nectarOportunidadeId(OPORTUNIDADE)
                .banco(banco)
                .build());
    }

    /** O dublê responde "a oportunidade está nesta etapa", que é o que a idempotência consulta. */
    private void estaNaEtapa(long etapaId) {
        when(nectarClient.oportunidade(OPORTUNIDADE))
                .thenReturn(Map.of("etapaAtual", Map.of("id", etapaId)));
        when(nectarClient.nomeDaEtapa(anyLong(), anyLong())).thenReturn("Etapa");
        when(nectarClient.nomeDaEtapa(eq(FUNIL_PROJETOS), eq(etapaId))).thenReturn("Etapa Atual");
    }

    /** Ordenada por id: {@code findAll} não promete ordem, e aqui a última é o que se afirma. */
    private NectarEtapaSincronizacao ultima() {
        List<NectarEtapaSincronizacao> todas = sincronizacaoRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(NectarEtapaSincronizacao::getId))
                .toList();
        assertThat(todas).isNotEmpty();
        return todas.get(todas.size() - 1);
    }

    private List<NectarEtapaSincronizacao> pendentes() {
        return sincronizacaoRepository.falhasPendentes(
                org.springframework.data.domain.PageRequest.of(0, 50));
    }
}
