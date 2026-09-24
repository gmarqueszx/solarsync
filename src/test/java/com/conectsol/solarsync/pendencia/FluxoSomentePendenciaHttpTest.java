package com.conectsol.solarsync.pendencia;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.conectsol.solarsync.debito.DebitoRepository;
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.jayway.jsonpath.JsonPath;

/**
 * O cliente avulso que o gestor manda ao setor só para resolver uma pendência: entrada →
 * pendência → resolvida → fim.
 * <p>
 * O teste prova os dois lados da mesma automação, e é o segundo que importa: o cliente normal
 * <b>continua</b> ganhando projeto quando a pendência é resolvida. A regra nova não podia
 * desligar o avanço automático de etapa, que é a integração entre etapas da seção 3.
 */
@AutoConfigureMockMvc
class FluxoSomentePendenciaHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String EMAIL = "analista.avulso@conectsol.com";

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
    private PendenciaRepository pendenciaRepository;

    @Autowired
    private DebitoRepository debitoRepository;

    @Autowired
    private HistoricoStatusRepository historicoStatusRepository;

    private Long usuarioCriadoId;
    private String token;

    @BeforeEach
    void autenticar() throws Exception {
        Usuario analista = Usuario.builder()
                .nome("Analista do Avulso")
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
        debitoRepository.deleteAll();
        projetoRepository.deleteAll();
        pendenciaRepository.deleteAll();
        if (usuarioCriadoId != null) {
            usuarioRepository.deleteById(usuarioCriadoId);
            usuarioCriadoId = null;
        }
        clienteRepository.deleteAll();
    }

    /**
     * O fluxo normal, que a regra nova não pode ter quebrado: pendência resolvida cria o projeto
     * do cliente em RECEBIDO.
     */
    @Test
    void clienteNormalGanhaProjetoAoResolverAPendencia() throws Exception {
        int cliente = criarCliente("Cliente do Fluxo Completo", false);
        int pendencia = abrirPendencia(cliente);
        registrarDebitoQuitado(cliente, "PENDENCIA");

        requisicao(post("/api/pendencias/%d/resolver".formatted(pendencia)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVIDA"));

        assertThat(projetoRepository.findByClienteId((long) cliente))
                .as("o avanço automático de etapa continua valendo para o cliente normal")
                .hasSize(1);
    }

    /**
     * O cliente avulso chega até a pendência e para ali. Nenhum projeto nasce — e é justamente
     * essa ausência que fecha o fluxo dele, porque não há mais etapa a cumprir.
     */
    @Test
    void clienteSomentePendenciaEncerraOFluxoAoResolver() throws Exception {
        int cliente = criarCliente("Padaria Avulsa", true);
        int pendencia = abrirPendencia(cliente);
        registrarDebitoQuitado(cliente, "PENDENCIA");

        requisicao(post("/api/pendencias/%d/resolver".formatted(pendencia)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVIDA"));

        assertThat(projetoRepository.findByClienteId((long) cliente))
                .as("resolver a pendência do avulso encerra o processo em vez de abrir o próximo")
                .isEmpty();
    }

    /**
     * O outro caminho automático de criação de projeto — a triagem sem pendência — respeita a
     * mesma regra. A guarda vive no {@code ProjetoService}, não nos listeners, exatamente para
     * cobrir os dois de uma vez.
     */
    @Test
    void clienteSomentePendenciaNaoGanhaProjetoNemPelaTriagemSemPendencia() throws Exception {
        int cliente = criarCliente("Oficina Avulsa", true);

        requisicao(post("/api/clientes/%d/sem-pendencia".formatted(cliente)), "")
                .andExpect(status().isOk());

        assertThat(projetoRepository.findByClienteId((long) cliente)).isEmpty();
    }

    /**
     * A criação à mão também é barrada, senão o botão "Novo Projeto" contornaria o fluxo curto
     * sem ninguém notar que o cliente era avulso. A recusa tem código próprio porque a saída é
     * específica: desmarcar a opção no cadastro.
     */
    @Test
    void criarProjetoAMaoParaAvulsoEhRecusado() throws Exception {
        int cliente = criarCliente("Avulso Teimoso", true);

        requisicao(post("/api/projetos"), """
                {"clienteId": %d, "tipoProjeto": "PROJETO_INICIAL"}""".formatted(cliente))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_SOMENTE_PENDENCIA"));
    }

    /**
     * Não é beco sem saída: o avulso que no meio do caminho virou projeto de verdade volta ao
     * fluxo completo desmarcando a opção no cadastro. É por isso que a flag fica no
     * {@code ClienteRequest} e não num endpoint de ação irreversível.
     */
    @Test
    void desmarcarAOpcaoDevolveOClienteAoFluxoCompleto() throws Exception {
        int cliente = criarCliente("Virou Projeto", true);

        requisicao(put("/api/clientes/" + cliente), """
                {"nome": "Virou Projeto", "somentePendencia": false}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.somentePendencia").value(false));

        requisicao(post("/api/projetos"), """
                {"clienteId": %d, "tipoProjeto": "PROJETO_INICIAL"}""".formatted(cliente))
                .andExpect(status().isCreated());
    }

    private int criarCliente(String nome, boolean somentePendencia) throws Exception {
        String corpo = requisicao(post("/api/clientes"), """
                {"nome": "%s", "cidade": "Salvador", "somentePendencia": %s}"""
                .formatted(nome, somentePendencia))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.somentePendencia").value(somentePendencia))
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

    /** Resolver pendência exige a consulta do débito daquela etapa; isto é o pré-requisito. */
    private void registrarDebitoQuitado(int clienteId, String tipo) throws Exception {
        requisicao(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "%s", "status": "QUITADO"}""".formatted(tipo))
                .andExpect(status().isOk());
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
