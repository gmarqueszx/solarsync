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
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
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

    @Autowired
    private HistoricoStatusRepository historicoStatusRepository;

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

    /**
     * O histórico precisa sair primeiro: desde que o desligamento passou a publicar evento, há
     * linhas de historico_status apontando para o usuário do teste, e a FK impede apagá-lo.
     */
    @AfterEach
    void limpar() {
        historicoStatusRepository.deleteAll();
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
    void doPedidoDeDesligamentoAteOMedidorDesligado() throws Exception {
        String criada = autenticada(post("/api/unificacoes"), """
                {"clienteId": %d, "informacoes": "Unificar duas UCs do mesmo terreno",
                 "projetistaId": %d}""".formatted(clienteId, usuarioCriadoId))
                .andExpect(status().isCreated())
                // Sem cidade no corpo, herda a do cliente.
                .andExpect(jsonPath("$.cidade").value("Feira de Santana"))
                .andExpect(jsonPath("$.feita").value(false))
                .andExpect(jsonPath("$.desligamentoStatus").value("NAO_SOLICITADO"))
                .andExpect(jsonPath("$.projetista.nome").value("Analista da Unificação"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(criada, "$.id");

        // A ordem importa: pedir desligamento antes de confirmar a unificação desligaria um
        // medidor de que o cliente ainda depende.
        autenticada(post("/api/unificacoes/%d/solicitar-desligamento".formatted(id)), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("UNIFICACAO_NAO_FEITA"));

        // Fila 1: o que falta unificar.
        mvc.perform(get("/api/unificacoes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("feita", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1));

        autenticada(post("/api/unificacoes/%d/concluir".formatted(id)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feita").value(true))
                // Concluir a unificação não pede o desligamento sozinho.
                .andExpect(jsonPath("$.desligamentoStatus").value("NAO_SOLICITADO"));

        // Fila 2: unificado e ainda falta pedir o desligamento.
        mvc.perform(get("/api/unificacoes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("feita", "true").param("desligamentoStatus", "NAO_SOLICITADO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1));

        autenticada(post("/api/unificacoes/%d/solicitar-desligamento".formatted(id)), """
                {"data": "2026-08-20"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desligamentoStatus").value("SOLICITADO"))
                .andExpect(jsonPath("$.desligamentoSolicitadoEm").value("2026-08-20"));

        // Fila 3: aguardando retorno da equipe de campo — a que mais se perde de vista.
        mvc.perform(get("/api/unificacoes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("desligamentoStatus", "SOLICITADO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1));

        autenticada(post("/api/unificacoes/%d/concluir-desligamento".formatted(id)), """
                {"data": "2026-08-30"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desligamentoStatus").value("CONCLUIDO"))
                .andExpect(jsonPath("$.desligamentoConcluidoEm").value("2026-08-30"));

        // Nada mais pendente em nenhuma fila.
        mvc.perform(get("/api/unificacoes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("desligamentoStatus", "NAO_SOLICITADO,SOLICITADO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(0));

        // O ciclo inteiro auditado, que é de onde sai o tempo de espera.
        mvc.perform(get("/api/unificacoes/%d".formatted(id))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desligamentoStatus").value("CONCLUIDO"));
    }

    @Test
    void quandoAEquipeNaoRealizaODesligamentoAbreSeOs() throws Exception {
        String criada = autenticada(post("/api/unificacoes"), """
                {"clienteId": %d}""".formatted(clienteId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(criada, "$.id");

        autenticada(post("/api/unificacoes/%d/concluir".formatted(id)), "{}")
                .andExpect(status().isOk());
        autenticada(post("/api/unificacoes/%d/solicitar-desligamento".formatted(id)), "{}")
                .andExpect(status().isOk());

        autenticada(post("/api/unificacoes/%d/abrir-os".formatted(id)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desligamentoStatus").value("OS_ABERTA"));

        // Da O.S. ainda se chega ao desligamento concluído.
        autenticada(post("/api/unificacoes/%d/concluir-desligamento".formatted(id)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desligamentoStatus").value("CONCLUIDO"));

        // E depois de concluído não há volta.
        autenticada(post("/api/unificacoes/%d/abrir-os".formatted(id)), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("TRANSICAO_INVALIDA"));
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
