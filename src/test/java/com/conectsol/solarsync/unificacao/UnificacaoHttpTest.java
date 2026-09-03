package com.conectsol.solarsync.unificacao;

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
import com.jayway.jsonpath.JsonPath;

@AutoConfigureMockMvc
class UnificacaoHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String EMAIL = "analista.unificacao@conectsol.com";

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
    private UnificacaoRepository unificacaoRepository;

    private Long usuarioCriadoId;
    private String token;
    private Integer clienteId;

    @BeforeEach
    void preparar() throws Exception {
        Usuario analista = Usuario.builder()
                .nome("Analista da Unificação")
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
                {"nome": "Cliente da Unificação", "cidade": "Feira de Santana"}""")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        clienteId = JsonPath.read(cliente, "$.id");
    }

    @AfterEach
    void limpar() {
        unificacaoRepository.deleteAll();
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
    void dosDoisMarcosIndependentesAsFilasDeTrabalho() throws Exception {
        String criada = autenticada(post("/api/unificacoes"), """
                {"clienteId": %d, "informacoes": "Unificar duas UCs do mesmo terreno",
                 "projetistaId": %d}""".formatted(clienteId, usuarioCriadoId))
                .andExpect(status().isCreated())
                // Sem cidade no corpo, herda a do cliente.
                .andExpect(jsonPath("$.cidade").value("Feira de Santana"))
                .andExpect(jsonPath("$.feita").value(false))
                .andExpect(jsonPath("$.desligamento").value(false))
                .andExpect(jsonPath("$.projetista.nome").value("Analista da Unificação"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(criada, "$.id");

        // Fila de trabalho: o que ainda não foi feito.
        mvc.perform(get("/api/unificacoes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("feita", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1));

        autenticada(post("/api/unificacoes/%d/concluir".formatted(id)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feita").value(true))
                // Os marcos são independentes: concluir não desliga o medidor.
                .andExpect(jsonPath("$.desligamento").value(false));

        // Segunda fila: unificado, mas com medidor antigo ainda ligado.
        mvc.perform(get("/api/unificacoes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("feita", "true").param("desligamento", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1));

        autenticada(post("/api/unificacoes/%d/registrar-desligamento".formatted(id)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desligamento").value(true));

        // Nada mais pendente em nenhuma das duas filas.
        mvc.perform(get("/api/unificacoes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("feita", "true").param("desligamento", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(0));
    }

    @Test
    void reabrirCorrigeMarcacaoErrada() throws Exception {
        String criada = autenticada(post("/api/unificacoes"), """
                {"clienteId": %d}""".formatted(clienteId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(criada, "$.id");

        autenticada(post("/api/unificacoes/%d/concluir".formatted(id)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feita").value(true));

        autenticada(post("/api/unificacoes/%d/reabrir".formatted(id)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feita").value(false));
    }

    @Test
    void analistaNaoPodeExcluirUnificacao() throws Exception {
        String criada = autenticada(post("/api/unificacoes"), """
                {"clienteId": %d}""".formatted(clienteId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(criada, "$.id");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/unificacoes/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
    }
}
