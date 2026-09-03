package com.conectsol.solarsync.vistoria;

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
import com.jayway.jsonpath.JsonPath;

/**
 * Etapa 4 ponta a ponta: instalação registrada à mão, vistoria solicitada, reprovada,
 * resolicitada e aprovada — fechando o ciclo do cliente.
 */
@AutoConfigureMockMvc
class VistoriaEtapa4HttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String EMAIL = "analista.vistoria@conectsol.com";

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

    private Long usuarioCriadoId;
    private String token;
    private Integer projetoId;

    @BeforeEach
    void prepararProjeto() throws Exception {
        Usuario analista = Usuario.builder()
                .nome("Analista da Vistoria")
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

        String cliente = autenticada(post("/api/clientes"), """
                {"nome": "Cliente da Vistoria", "cidade": "Salvador"}""")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer clienteId = JsonPath.read(cliente, "$.id");

        String projeto = autenticada(post("/api/projetos"), """
                {"clienteId": %d, "tipoProjeto": "PADRAO"}""".formatted(clienteId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projetoId = JsonPath.read(projeto, "$.id");
    }

    @AfterEach
    void limpar() {
        historicoStatusRepository.deleteAll();
        vistoriaRepository.deleteAll();
        debitoRepository.deleteAll();
        projetoRepository.deleteAll();
        pendenciaRepository.deleteAll();
        if (usuarioCriadoId != null) {
            usuarioRepository.deleteById(usuarioCriadoId);
            usuarioCriadoId = null;
        }
        clienteRepository.deleteAll();
    }

    private ResultActions autenticada(MockHttpServletRequestBuilder builder, String corpo)
            throws Exception {
        return mvc.perform(builder
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    @Test
    void semInstalacaoRegistradaNaoSePodeSolicitarVistoria() throws Exception {
        autenticada(post("/api/vistorias"), """
                {"projetoId": %d}""".formatted(projetoId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PROJETO_SEM_INSTALACAO"));
    }

    @Test
    void doRegistroDaInstalacaoAoCicloFechado() throws Exception {
        // A fila de trabalho da Nycole começa vazia: nada instalado ainda.
        mvc.perform(get("/api/projetos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("instalado", "true").param("semVistoria", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(0));

        // Nycole registra a data que recolheu no grupo — passada, não "hoje".
        autenticada(post("/api/projetos/%d/registrar-instalacao".formatted(projetoId)), """
                {"dataInstalacao": "2026-08-15"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataInstalacao").value("2026-08-15"))
                // Registrar instalação não mexe no status da homologação.
                .andExpect(jsonPath("$.status").value("RECEBIDO"));

        // Agora aparece na fila: instalado e sem vistoria.
        mvc.perform(get("/api/projetos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("instalado", "true").param("semVistoria", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].dataInstalacao").value("2026-08-15"));

        String vistoria = autenticada(post("/api/vistorias"), """
                {"projetoId": %d, "dataSolicitacao": "2026-08-20"}""".formatted(projetoId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SOLICITADA"))
                .andExpect(jsonPath("$.cliente.nome").value("Cliente da Vistoria"))
                // As duas pontas da métrica "tempo para solicitar vistoria pós-instalação".
                .andExpect(jsonPath("$.dataInstalacaoDoProjeto").value("2026-08-15"))
                .andExpect(jsonPath("$.dataSolicitacao").value("2026-08-20"))
                .andReturn().getResponse().getContentAsString();
        Integer vistoriaId = JsonPath.read(vistoria, "$.id");

        // Saiu da fila.
        mvc.perform(get("/api/projetos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("instalado", "true").param("semVistoria", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(0));

        autenticada(post("/api/vistorias/%d/reprovar".formatted(vistoriaId)), """
                {"dataResultado": "2026-08-25"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPROVADA"))
                .andExpect(jsonPath("$.dataResultado").value("2026-08-25"));

        autenticada(post("/api/vistorias/%d/resolicitar".formatted(vistoriaId)), """
                {"projetoId": %d, "dataSolicitacao": "2026-09-01"}""".formatted(projetoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLICITADA"))
                .andExpect(jsonPath("$.dataSolicitacao").value("2026-09-01"))
                // O resultado antigo é limpo: senão pareceria já ter resposta da nova solicitação.
                .andExpect(jsonPath("$.dataResultado").doesNotExist());

        autenticada(post("/api/vistorias/%d/aprovar".formatted(vistoriaId)), """
                {"dataResultado": "2026-09-10"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROVADA"));

        // Todas as idas e vindas no mesmo registro, como o gestor precisa ver.
        mvc.perform(get("/api/vistorias/%d/historico".formatted(vistoriaId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].statusNovo").value("SOLICITADA"))
                .andExpect(jsonPath("$[1].statusNovo").value("REPROVADA"))
                .andExpect(jsonPath("$[2].statusNovo").value("SOLICITADA"))
                .andExpect(jsonPath("$[3].statusNovo").value("APROVADA"));
    }

    @Test
    void vistoriaAprovadaNaoVoltaAtras() throws Exception {
        autenticada(post("/api/projetos/%d/registrar-instalacao".formatted(projetoId)), """
                {"dataInstalacao": "2026-08-15"}""")
                .andExpect(status().isOk());

        String vistoria = autenticada(post("/api/vistorias"), """
                {"projetoId": %d}""".formatted(projetoId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer vistoriaId = JsonPath.read(vistoria, "$.id");

        autenticada(post("/api/vistorias/%d/aprovar".formatted(vistoriaId)), "{}")
                .andExpect(status().isOk());

        autenticada(post("/api/vistorias/%d/reprovar".formatted(vistoriaId)), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("TRANSICAO_INVALIDA"));
    }

    @Test
    void instalacaoNoFuturoERecusada() throws Exception {
        autenticada(post("/api/projetos/%d/registrar-instalacao".formatted(projetoId)), """
                {"dataInstalacao": "2099-01-01"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACAO"));
    }
}
