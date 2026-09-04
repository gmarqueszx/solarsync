package com.conectsol.solarsync.cliente;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.conectsol.solarsync.auth.NomePapel;
import com.conectsol.solarsync.auth.PapelRepository;
import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.common.AbstractIntegrationTest;
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.jayway.jsonpath.JsonPath;

/**
 * Campos que o protótipo do frontend assumia e o modelo não tinha: potência em kWp, UC Coelba e
 * telefone. Confirmados com o usuário como informação real, então precisam trafegar pela API de
 * verdade — este teste é o que garante que não voltem a sumir do contrato.
 */
@AutoConfigureMockMvc
class CamposDoContratoHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String EMAIL = "analista.campos@conectsol.com";

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
    private ProjetoRepository projetoRepository;

    @Autowired
    private HistoricoStatusRepository historicoStatusRepository;

    private Long usuarioCriadoId;
    private String token;

    @BeforeEach
    void autenticar() throws Exception {
        usuarioCriadoId = usuarioRepository.save(Usuario.builder()
                .nome("Analista dos Campos").email(EMAIL).ativo(true)
                .senhaHash(passwordEncoder.encode(SENHA))
                .papeis(Set.of(papelRepository.findByNome(NomePapel.ANALISTA).orElseThrow()))
                .build()).getId();

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
        projetoRepository.deleteAll();
        if (usuarioCriadoId != null) {
            usuarioRepository.deleteById(usuarioCriadoId);
            usuarioCriadoId = null;
        }
        clienteRepository.deleteAll();
    }

    private ResultActions enviar(String caminho, String corpo) throws Exception {
        return mvc.perform(post(caminho)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    @Test
    void ucCoelbaETelefoneTrafegamNoCliente() throws Exception {
        String criado = enviar("/api/clientes", """
                {"nome": "Cliente com UC", "cidade": "Salvador",
                 "ucCoelba": "3009876543", "telefone": "(71) 98888-7777"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ucCoelba").value("3009876543"))
                .andExpect(jsonPath("$.telefone").value("(71) 98888-7777"))
                .andReturn().getResponse().getContentAsString();
        Integer clienteId = JsonPath.read(criado, "$.id");

        mvc.perform(get("/api/clientes/" + clienteId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ucCoelba").value("3009876543"));
    }

    @Test
    void buscaDeClienteAchaPorNomeOuPorUc() throws Exception {
        enviar("/api/clientes", """
                {"nome": "Joana Ribeiro", "ucCoelba": "3005550001"}""")
                .andExpect(status().isCreated());
        enviar("/api/clientes", """
                {"nome": "Pedro Antunes", "ucCoelba": "3005550002"}""")
                .andExpect(status().isCreated());

        // O analista digita nome ou UC no mesmo campo — é como a Coelba identifica o cliente.
        mvc.perform(get("/api/clientes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("nome", "Joana"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].nome").value("Joana Ribeiro"));

        mvc.perform(get("/api/clientes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("nome", "3005550002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].nome").value("Pedro Antunes"));
    }

    @Test
    void potenciaKwpTrafegaNoProjetoEApareceNaListagem() throws Exception {
        String cliente = enviar("/api/clientes", """
                {"nome": "Cliente da Usina", "ucCoelba": "3001112223"}""")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer clienteId = JsonPath.read(cliente, "$.id");

        String projeto = enviar("/api/projetos", """
                {"clienteId": %d, "tipoProjeto": "PADRAO", "potenciaKwp": 12.60}"""
                .formatted(clienteId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.potenciaKwp").value(12.60))
                // A UC vem junto no resumo do cliente, para a tela do projeto identificá-lo.
                .andExpect(jsonPath("$.cliente.ucCoelba").value("3001112223"))
                .andReturn().getResponse().getContentAsString();
        Integer projetoId = JsonPath.read(projeto, "$.id");

        mvc.perform(get("/api/projetos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("clienteId", String.valueOf(clienteId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo[0].potenciaKwp").value(12.60));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/projetos/" + projetoId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"tipoProjeto": "AMPLIACAO", "potenciaKwp": 20.00}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.potenciaKwp").value(20.00));
    }

    @Test
    void potenciaNegativaERecusada() throws Exception {
        String cliente = enviar("/api/clientes", """
                {"nome": "Cliente Potência Inválida"}""")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        enviar("/api/projetos", """
                {"clienteId": %d, "tipoProjeto": "PADRAO", "potenciaKwp": -5}"""
                .formatted((Integer) JsonPath.read(cliente, "$.id")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACAO"));
    }
}
