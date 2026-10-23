package com.conectsol.solarsync.pendencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.jayway.jsonpath.JsonPath;

/**
 * As duas colunas pedidas pela equipe de Projetos: o número da solicitação que alguns tipos de
 * pendência geram na Coelba, e o status de acompanhamento de quem trabalha a pendência.
 * <p>
 * O que mais importa aqui é a fronteira: o acompanhamento <b>não</b> é o ciclo de vida. Mudá-lo
 * não pode mexer no status nem gravar histórico, senão uma anotação de trabalho entraria nas
 * métricas do dashboard.
 */
@AutoConfigureMockMvc
class PendenciaNumeroEAcompanhamentoHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String EMAIL = "analista.acompanhamento@conectsol.com";

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
    private HistoricoStatusRepository historicoStatusRepository;

    private Long usuarioCriadoId;
    private String token;

    @BeforeEach
    void autenticar() throws Exception {
        Usuario analista = Usuario.builder()
                .nome("Analista do Acompanhamento")
                .email(EMAIL)
                .ativo(true)
                .senhaHash(passwordEncoder.encode(SENHA))
                .papeis(Set.of(papelRepository.findByNome(NomePapel.ANALISTA).orElseThrow()))
                .build();
        usuarioCriadoId = usuarioRepository.save(analista).getId();

        String login = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "senha": "%s"}""".formatted(EMAIL, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(login, "$.accessToken");
    }

    @AfterEach
    void limpar() {
        historicoStatusRepository.deleteAll();
        pendenciaRepository.deleteAll();
        if (usuarioCriadoId != null) {
            usuarioRepository.deleteById(usuarioCriadoId);
            usuarioCriadoId = null;
        }
        clienteRepository.deleteAll();
    }

    /** Os dois campos são opcionais: o corpo de antes continua criando a pendência. */
    @Test
    void criarSemOsCamposNovosContinuaFuncionando() throws Exception {
        int cliente = criarCliente("Cliente Sem Número");

        requisicao(post("/api/pendencias"), """
                {"clienteId": %d, "tipo": "LIGACAO_NOVA"}""".formatted(cliente))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numeroSolicitacao").doesNotExist())
                .andExpect(jsonPath("$.statusAcompanhamento").doesNotExist());
    }

    @Test
    void numeroInformadoNaCriacaoApareceNaListagemEEhBuscavel() throws Exception {
        int cliente = criarCliente("Cliente Com Protocolo");
        requisicao(post("/api/pendencias"), """
                {"clienteId": %d, "tipo": "TROCA_TITULARIDADE",
                 "numeroSolicitacao": " 7012345678 ", "statusAcompanhamento": "PERIODO_DE_LEITURA"}"""
                .formatted(cliente))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numeroSolicitacao").value("7012345678"))
                .andExpect(jsonPath("$.statusAcompanhamento").value("PERIODO_DE_LEITURA"));

        requisicao(get("/api/pendencias?q=70123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo.length()").value(1))
                .andExpect(jsonPath("$.conteudo[0].numeroSolicitacao").value("7012345678"))
                .andExpect(jsonPath("$.conteudo[0].statusAcompanhamento")
                        .value("PERIODO_DE_LEITURA"));
    }

    /** O PUT corrige o número e, em branco, o apaga — sem tocar no acompanhamento. */
    @Test
    void editarCorrigeONumeroSemApagarOAcompanhamento() throws Exception {
        int cliente = criarCliente("Cliente Editado");
        int pendencia = abrirPendencia(cliente);
        requisicao(put("/api/pendencias/%d/acompanhamento".formatted(pendencia)), """
                {"statusAcompanhamento": "STAND_BY"}""")
                .andExpect(status().isOk());

        requisicao(put("/api/pendencias/" + pendencia), """
                {"tipo": "LIGACAO_NOVA", "numeroSolicitacao": "123"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numeroSolicitacao").value("123"))
                .andExpect(jsonPath("$.statusAcompanhamento").value("STAND_BY"));

        requisicao(put("/api/pendencias/" + pendencia), """
                {"tipo": "LIGACAO_NOVA", "numeroSolicitacao": "  "}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numeroSolicitacao").doesNotExist());
    }

    @Test
    void acompanhamentoNaoMudaOStatusNemGravaHistorico() throws Exception {
        int cliente = criarCliente("Cliente Acompanhado");
        int pendencia = abrirPendencia(cliente);
        long historicoAntes = historicoStatusRepository.count();

        requisicao(put("/api/pendencias/%d/acompanhamento".formatted(pendencia)), """
                {"statusAcompanhamento": "NOTA_RETIDA"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusAcompanhamento").value("NOTA_RETIDA"))
                .andExpect(jsonPath("$.status").value("ABERTA"));

        assertThat(historicoStatusRepository.count())
                .as("anotação de acompanhamento não é transição de status")
                .isEqualTo(historicoAntes);

        requisicao(put("/api/pendencias/%d/acompanhamento".formatted(pendencia)), """
                {"statusAcompanhamento": null}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusAcompanhamento").doesNotExist());
    }

    @Test
    void listagemFiltraPorAcompanhamento() throws Exception {
        int obra = abrirPendencia(criarCliente("Cliente em Obra"));
        abrirPendencia(criarCliente("Cliente Sem Anotação"));
        requisicao(put("/api/pendencias/%d/acompanhamento".formatted(obra)), """
                {"statusAcompanhamento": "OBRA"}""")
                .andExpect(status().isOk());

        requisicao(get("/api/pendencias?statusAcompanhamento=OBRA,B_OPTANTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo.length()").value(1))
                .andExpect(jsonPath("$.conteudo[0].id").value(obra));
    }

    @Test
    void valorForaDaListaEhRecusado() throws Exception {
        int pendencia = abrirPendencia(criarCliente("Cliente Inventivo"));

        requisicao(put("/api/pendencias/%d/acompanhamento".formatted(pendencia)), """
                {"statusAcompanhamento": "QUALQUER_COISA"}""")
                .andExpect(status().isBadRequest());
    }

    private int criarCliente(String nome) throws Exception {
        String corpo = requisicao(post("/api/clientes"), """
                {"nome": "%s", "cidade": "Salvador"}""".formatted(nome))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.id");
    }

    private int abrirPendencia(int clienteId) throws Exception {
        String corpo = requisicao(post("/api/pendencias"), """
                {"clienteId": %d, "tipo": "REGULARIZACAO_CADASTRAL"}""".formatted(clienteId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.id");
    }

    private ResultActions requisicao(MockHttpServletRequestBuilder builder, String corpo)
            throws Exception {
        return mvc.perform(builder
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    private ResultActions requisicao(MockHttpServletRequestBuilder builder) throws Exception {
        return requisicao(builder, "");
    }
}
