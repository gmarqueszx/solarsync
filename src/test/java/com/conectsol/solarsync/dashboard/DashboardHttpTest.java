package com.conectsol.solarsync.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.conectsol.solarsync.auth.NomePapel;
import com.conectsol.solarsync.auth.PapelRepository;
import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.common.AbstractIntegrationTest;
import com.conectsol.solarsync.debito.DebitoRepository;
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.pendencia.PendenciaRepository;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.conectsol.solarsync.vistoria.VistoriaRepository;
import com.jayway.jsonpath.JsonPath;

/**
 * Monta um cenário com datas conhecidas e confere os números exatos — um teste que só
 * verificasse "respondeu 200" não pegaria uma média calculada errado, que é justamente o bug
 * que faria o gestor perder a confiança no sistema.
 */
@AutoConfigureMockMvc
class DashboardHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PapelRepository papelRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private PendenciaRepository pendenciaRepository;

    @Autowired
    private ProjetoRepository projetoRepository;

    @Autowired
    private VistoriaRepository vistoriaRepository;

    @Autowired
    private DebitoRepository debitoRepository;

    @Autowired
    private HistoricoStatusRepository historicoStatusRepository;

    private Long gestorId;
    private Long analistaId;
    private String tokenGestor;
    private String tokenAnalista;

    private static String iso(int diasAtras) {
        return LocalDate.now().minusDays(diasAtras).toString();
    }

    @BeforeEach
    void prepararCenario() throws Exception {
        gestorId = criarUsuario("Igor Gestor", "igor.dash@conectsol.com", NomePapel.GESTOR);
        analistaId = criarUsuario("Ana Analista", "ana.dash@conectsol.com", NomePapel.ANALISTA);
        tokenGestor = login("igor.dash@conectsol.com");
        tokenAnalista = login("ana.dash@conectsol.com");

        // Projeto A: recebido há 60 dias, enviado há 50 (10 dias), aprovado há 40 (10 dias),
        // instalado há 30, vistoria pedida há 25 (5 dias) e aprovada há 20 (ciclo de 40 dias).
        Integer clienteA = criarCliente("Cliente A", 70);
        Integer projetoA = criarProjeto(clienteA, iso(60));
        acao("/api/projetos/%d/encaminhar".formatted(projetoA),
                """
                        {"dataEncaminhado": "%s"}""".formatted(iso(50)));
        acao("/api/projetos/%d/aprovar".formatted(projetoA),
                """
                        {"dataAprovacao": "%s"}""".formatted(iso(40)));
        acao("/api/projetos/%d/registrar-instalacao".formatted(projetoA),
                """
                        {"dataInstalacao": "%s"}""".formatted(iso(30)));
        String vistoriaA = postAutenticado("/api/vistorias", """
                {"projetoId": %d, "dataSolicitacao": "%s"}""".formatted(projetoA, iso(25)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        acao("/api/vistorias/%d/aprovar".formatted((Integer) JsonPath.read(vistoriaA, "$.id")),
                """
                        {"dataResultado": "%s"}""".formatted(iso(20)));

        // Projeto B: recebido há 30, enviado há 10 (20 dias) e reprovado. Sem aprovação.
        Integer clienteB = criarCliente("Cliente B", 35);
        Integer projetoB = criarProjeto(clienteB, iso(30));
        acao("/api/projetos/%d/encaminhar".formatted(projetoB),
                """
                        {"dataEncaminhado": "%s"}""".formatted(iso(10)));
        acao("/api/projetos/%d/reprovar".formatted(projetoB),
                """
                        {"motivo": "Faltou ART"}""");

        // Cliente C: só débito ativo, para o contador de travados.
        Integer clienteC = criarCliente("Cliente C", 20);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/debitos/cliente/" + clienteC)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenGestor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status": "ATIVO"}"""))
                .andExpect(status().isOk());
    }

    @AfterEach
    void limpar() {
        historicoStatusRepository.deleteAll();
        vistoriaRepository.deleteAll();
        debitoRepository.deleteAll();
        projetoRepository.deleteAll();
        pendenciaRepository.deleteAll();
        usuarioRepository.deleteById(gestorId);
        usuarioRepository.deleteById(analistaId);
        clienteRepository.deleteAll();
    }

    private Long criarUsuario(String nome, String email, NomePapel papel) {
        return usuarioRepository.save(Usuario.builder()
                .nome(nome).email(email).ativo(true)
                .senhaHash(passwordEncoder.encode(SENHA))
                .papeis(Set.of(papelRepository.findByNome(papel).orElseThrow()))
                .build()).getId();
    }

    private String login(String email) throws Exception {
        String corpo = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "senha": "%s"}""".formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.accessToken");
    }

    private ResultActions postAutenticado(String caminho, String corpo) throws Exception {
        return requisicao(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(caminho), corpo);
    }

    private void acao(String caminho, String corpo) throws Exception {
        postAutenticado(caminho, corpo).andExpect(status().isOk());
    }

    private ResultActions requisicao(MockHttpServletRequestBuilder builder, String corpo)
            throws Exception {
        return mvc.perform(builder
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenGestor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    private Integer criarCliente(String nome, int diasAtras) throws Exception {
        String corpo = postAutenticado("/api/clientes", """
                {"nome": "%s", "dataPagamento": "%s"}""".formatted(nome, iso(diasAtras)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.id");
    }

    private Integer criarProjeto(Integer clienteId, String dataRecebimento) throws Exception {
        String corpo = postAutenticado("/api/projetos", """
                {"clienteId": %d, "tipoProjeto": "PADRAO", "dataRecebimento": "%s"}"""
                .formatted(clienteId, dataRecebimento))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.id");
    }

    @Test
    void calculaOsTemposMediosEOsQuantitativos() throws Exception {
        mvc.perform(get("/api/dashboard")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenGestor))
                .andExpect(status().isOk())
                // Projeto A levou 10 dias do recebimento ao envio; B levou 20. Média: 15.
                .andExpect(jsonPath("$.temposMediosEmDias.recebimentoAteEnvio").value(15.0))
                // Só A foi aprovado: 50 - 40 = 10 dias.
                .andExpect(jsonPath("$.temposMediosEmDias.envioAteAprovacao").value(10.0))
                // Instalado há 30, vistoria pedida há 25.
                .andExpect(jsonPath("$.temposMediosEmDias.instalacaoAteSolicitarVistoria")
                        .value(5.0))
                // Recebido há 60, vistoria aprovada há 20.
                .andExpect(jsonPath("$.temposMediosEmDias.cicloCompleto").value(40.0))
                .andExpect(jsonPath("$.quantitativos.projetosEncaminhados").value(2))
                .andExpect(jsonPath("$.quantitativos.projetosAprovados").value(1))
                .andExpect(jsonPath("$.quantitativos.projetosReprovados").value(1))
                .andExpect(jsonPath("$.quantitativos.vistoriasSolicitadas").value(1))
                .andExpect(jsonPath("$.quantitativos.clientesComDebitoAtivo").value(1))
                // Contadores que o protótipo do frontend já mostrava e a API passou a devolver.
                .andExpect(jsonPath("$.quantitativos.vistoriasAprovadas").value(1))
                .andExpect(jsonPath("$.quantitativos.vistoriasReprovadas").value(0))
                .andExpect(jsonPath("$.quantitativos.projetosReencaminhados").value(0))
                .andExpect(jsonPath("$.quantitativos.clientesComDebitoQuitado").value(0))
                .andExpect(jsonPath("$.quantitativos.unificacoesPendentes").value(0))
                .andExpect(jsonPath("$.quantitativos.pendenciasAbertasNoPeriodo").value(0));
    }

    @Test
    void semCasoNoPeriodoDevolveNuloEmVezDeZero() throws Exception {
        // Zero seria lido como "instantâneo"; nulo diz a verdade: não houve caso.
        mvc.perform(get("/api/dashboard")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenGestor)
                .param("de", iso(3)).param("ate", iso(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temposMediosEmDias.recebimentoAteEnvio").doesNotExist())
                .andExpect(jsonPath("$.temposMediosEmDias.cicloCompleto").doesNotExist())
                .andExpect(jsonPath("$.quantitativos.projetosAprovados").value(0));
    }

    @Test
    void oFiltroDePeriodoRecortaCadaMetricaPelaSuaDataDeReferencia() throws Exception {
        // Janela que pega só o envio do projeto B (há 10 dias), não o de A (há 50).
        mvc.perform(get("/api/dashboard")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenGestor)
                .param("de", iso(20)).param("ate", iso(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo.de").value(iso(20)))
                .andExpect(jsonPath("$.quantitativos.projetosEncaminhados").value(1))
                .andExpect(jsonPath("$.temposMediosEmDias.recebimentoAteEnvio").value(20.0))
                // A aprovação de A foi há 40 dias, fora da janela.
                .andExpect(jsonPath("$.quantitativos.projetosAprovados").value(0))
                .andExpect(jsonPath("$.temposMediosEmDias.envioAteAprovacao").doesNotExist())
                // Débito ativo ignora o período de propósito: é a situação de agora.
                .andExpect(jsonPath("$.quantitativos.clientesComDebitoAtivo").value(1));
    }

    @Test
    void analistaNaoVeODashboard() throws Exception {
        mvc.perform(get("/api/dashboard")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAnalista))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
    }

    @Test
    void semTokenNaoVeODashboard() throws Exception {
        mvc.perform(get("/api/dashboard"))
                .andExpect(status().isUnauthorized());
    }
}
