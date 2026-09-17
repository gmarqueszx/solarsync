package com.conectsol.solarsync.cliente;

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
import com.conectsol.solarsync.common.AbstractIntegrationTest;
import com.conectsol.solarsync.debito.DebitoRepository;
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.pendencia.PendenciaRepository;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.jayway.jsonpath.JsonPath;

/**
 * A etapa 1 completa, ponta a ponta: todo cliente é checado, os dois desfechos da checagem são
 * registrados, e resolver pendência exige saber que o cliente não deve.
 * <p>
 * Sem {@code @Transactional} e com limpeza no {@code @AfterEach}: o listener que cria o projeto
 * roda em AFTER_COMMIT, então a transação precisa realmente comitar.
 */
@AutoConfigureMockMvc
class TriagemPendenciaHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String EMAIL = "analista.triagem@conectsol.com";

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
    private DebitoRepository debitoRepository;

    @Autowired
    private HistoricoStatusRepository historicoStatusRepository;

    private Long usuarioCriadoId;
    private String token;

    @BeforeEach
    void autenticar() throws Exception {
        Usuario analista = Usuario.builder()
                .nome("Analista da Triagem")
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
        // historico_status antes dos usuários: a FK usuario_id recusaria a exclusão.
        historicoStatusRepository.deleteAll();
        debitoRepository.deleteAll();
        projetoRepository.deleteAll();
        pendenciaRepository.deleteAll();
        if (usuarioCriadoId != null) {
            usuarioRepository.deleteById(usuarioCriadoId);
            usuarioCriadoId = null;
        }
        clienteRepository.deleteAll();
    }

    @Test
    void clienteNasceNaFilaDeVerificacaoEMarcarSemPendenciaCriaOProjeto() throws Exception {
        Integer clienteId = criarCliente("Cliente Sem Pendência");

        // Todo cliente entra na fila: é o requisito "todos os clientes sejam checados".
        autenticada(get("/api/clientes").param("statusTriagem", "AGUARDANDO_VERIFICACAO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].id").value(clienteId));

        // A checagem deu "não tem pendência": o cliente segue direto.
        autenticada(post("/api/clientes/%d/sem-pendencia".formatted(clienteId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusTriagem").value("SEM_PENDENCIA"));

        // Saiu da fila da triagem e caiu na fila da consulta de débito.
        autenticada(get("/api/clientes").param("statusTriagem", "AGUARDANDO_VERIFICACAO"))
                .andExpect(jsonPath("$.totalElementos").value(0));
        autenticada(get("/api/clientes").param("semConsultaDebito", "true"))
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].id").value(clienteId));

        // E o projeto nasceu sozinho, sem pendência nenhuma no caminho.
        autenticada(get("/api/projetos").param("clienteId", String.valueOf(clienteId)))
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].status").value("RECEBIDO"));

        // A triagem ficou auditada no histórico do cliente.
        autenticada(get("/api/clientes/%d/historico".formatted(clienteId)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].statusAnterior").value("AGUARDANDO_VERIFICACAO"))
                .andExpect(jsonPath("$[0].statusNovo").value("SEM_PENDENCIA"))
                .andExpect(jsonPath("$[0].usuarioId").value(usuarioCriadoId.intValue()));

        // Repetir é no-op idempotente: não duplica histórico nem cria um segundo projeto.
        autenticada(post("/api/clientes/%d/sem-pendencia".formatted(clienteId)))
                .andExpect(status().isOk());
        autenticada(get("/api/clientes/%d/historico".formatted(clienteId)))
                .andExpect(jsonPath("$.length()").value(1));
        autenticada(get("/api/projetos").param("clienteId", String.valueOf(clienteId)))
                .andExpect(jsonPath("$.totalElementos").value(1));
    }

    @Test
    void criarPendenciaMarcaOClienteComoChecadoComPendencia() throws Exception {
        Integer clienteId = criarCliente("Cliente Com Pendência");

        autenticadaComCorpo(post("/api/pendencias"), """
                {"clienteId": %d, "tipo": "LIGACAO_NOVA"}""".formatted(clienteId))
                .andExpect(status().isCreated());

        // Ninguém precisou marcar a triagem à mão: a pendência é a prova da checagem. É o que
        // evita reintroduzir o retrabalho de atualizar a situação em dois lugares.
        autenticada(get("/api/clientes/" + clienteId))
                .andExpect(jsonPath("$.statusTriagem").value("COM_PENDENCIA"));
        autenticada(get("/api/clientes").param("statusTriagem", "AGUARDANDO_VERIFICACAO"))
                .andExpect(jsonPath("$.totalElementos").value(0));
    }

    @Test
    void resolverPendenciaExigeConsultaDeDebitoEClienteSemDebito() throws Exception {
        Integer clienteId = criarCliente("Cliente Travado");

        String pendencia = autenticadaComCorpo(post("/api/pendencias"), """
                {"clienteId": %d, "tipo": "TROCA_TITULARIDADE"}""".formatted(clienteId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer pendenciaId = JsonPath.read(pendencia, "$.id");

        // 1. Ninguém consultou a agência virtual: não se sabe se o cliente deve.
        autenticada(post("/api/pendencias/%d/resolver".formatted(pendenciaId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DEBITO_NAO_CONSULTADO"));
        autenticada(get("/api/pendencias").param("semConsultaDebito", "true"))
                .andExpect(jsonPath("$.totalElementos").value(1));

        // 2. Consultou e o cliente deve: a pendência fica travada, mas não muda de status.
        autenticadaComCorpo(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "PENDENCIA", "status": "ATIVO"}""")
                .andExpect(status().isOk());

        autenticada(post("/api/pendencias/%d/resolver".formatted(pendenciaId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_COM_DEBITO"));

        autenticada(get("/api/pendencias/" + pendenciaId))
                .andExpect(jsonPath("$.status").value("ABERTA"));
        autenticada(get("/api/pendencias").param("travadaPorDebito", "true"))
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].id").value(pendenciaId));

        // 3. Quitou: agora resolve, e a automação cria o projeto.
        autenticadaComCorpo(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "PENDENCIA", "status": "QUITADO"}""")
                .andExpect(status().isOk());

        autenticada(post("/api/pendencias/%d/resolver".formatted(pendenciaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVIDA"));

        autenticada(get("/api/pendencias").param("travadaPorDebito", "true"))
                .andExpect(jsonPath("$.totalElementos").value(0));
        autenticada(get("/api/projetos").param("clienteId", String.valueOf(clienteId)))
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].status").value("RECEBIDO"));
    }

    @Test
    void reverificarDevolveOClienteParaAFilaSemApagarOProjeto() throws Exception {
        Integer clienteId = criarCliente("Cliente Do Novo Ciclo");

        autenticada(post("/api/clientes/%d/sem-pendencia".formatted(clienteId)))
                .andExpect(status().isOk());
        autenticada(post("/api/clientes/%d/reverificar".formatted(clienteId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusTriagem").value("AGUARDANDO_VERIFICACAO"));

        autenticada(get("/api/clientes").param("statusTriagem", "AGUARDANDO_VERIFICACAO"))
                .andExpect(jsonPath("$.totalElementos").value(1));

        // O projeto criado pela triagem anterior continua lá: reverificar é sobre a checagem,
        // não sobre desfazer trabalho já feito.
        autenticada(get("/api/projetos").param("clienteId", String.valueOf(clienteId)))
                .andExpect(jsonPath("$.totalElementos").value(1));
    }

    @Test
    void editarOCadastroNaoApagaOResultadoDaTriagem() throws Exception {
        Integer clienteId = criarCliente("Cliente Editado");

        autenticada(post("/api/clientes/%d/sem-pendencia".formatted(clienteId)))
                .andExpect(status().isOk());

        // O PUT substitui o cadastro inteiro e não tem campo statusTriagem: corrigir telefone
        // não pode, de passagem, devolver o cliente para a fila de verificação.
        autenticadaComCorpo(put("/api/clientes/" + clienteId), """
                {"nome": "Cliente Editado", "telefone": "(71) 90000-0000"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telefone").value("(71) 90000-0000"))
                .andExpect(jsonPath("$.statusTriagem").value("SEM_PENDENCIA"));
    }

    private Integer criarCliente(String nome) throws Exception {
        String corpo = autenticadaComCorpo(post("/api/clientes"), """
                {"nome": "%s", "cidade": "Salvador"}""".formatted(nome))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusTriagem").value("AGUARDANDO_VERIFICACAO"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.id");
    }

    private ResultActions autenticada(MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions autenticadaComCorpo(MockHttpServletRequestBuilder builder, String corpo)
            throws Exception {
        return mvc.perform(builder
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }
}
