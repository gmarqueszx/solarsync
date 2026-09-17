package com.conectsol.solarsync;

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
 * Prova ponta a ponta por HTTP: login real → token real → cadastro → resolução da pendência →
 * criação automática do projeto → auditoria. Junta domínio, HTTP e segurança num só teste.
 * <p>
 * Sem {@code @Transactional}: o listener que cria o projeto roda em {@code AFTER_COMMIT}, então
 * a transação precisa realmente comitar. A limpeza no {@code @AfterEach} apaga apenas o que
 * este teste criou — nunca os dados semeados pelas migrations.
 */
@AutoConfigureMockMvc
class FluxoPendenciaProjetoHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String EMAIL = "analista.fluxo@conectsol.com";

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
    private HistoricoStatusRepository historicoStatusRepository;

    @Autowired
    private DebitoRepository debitoRepository;

    private Long usuarioCriadoId;

    @BeforeEach
    void criarAnalista() {
        Usuario analista = Usuario.builder()
                .nome("Analista do Fluxo")
                .email(EMAIL)
                .ativo(true)
                .senhaHash(passwordEncoder.encode(SENHA))
                .papeis(Set.of(papelRepository.findByNome(NomePapel.ANALISTA).orElseThrow()))
                .build();
        usuarioCriadoId = usuarioRepository.save(analista).getId();
    }

    @AfterEach
    void limpar() {
        historicoStatusRepository.deleteAll();
        projetoRepository.deleteAll();
        pendenciaRepository.deleteAll();
        // Desde que resolver pendência exige consulta de débito, este teste cria uma linha em
        // `debito`; sem apagá-la, o `clienteRepository.deleteAll()` abaixo falha por FK — e o
        // estrago aparece no teste seguinte que apaga clientes, não neste.
        debitoRepository.deleteAll();
        if (usuarioCriadoId != null) {
            usuarioRepository.deleteById(usuarioCriadoId);
            usuarioCriadoId = null;
        }
        clienteRepository.deleteAll();
    }

    private String autenticar() throws Exception {
        String corpo = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "senha": "%s"}""".formatted(EMAIL, SENHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("Bearer"))
                .andExpect(jsonPath("$.usuario.papeis[0]").value("ANALISTA"))
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(corpo, "$.accessToken");
    }

    @Test
    void doLoginAoProjetoCriadoAutomaticamente() throws Exception {
        String token = autenticar();

        // 1. Cadastra o cliente
        String clienteJson = mvc.perform(post("/api/clientes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nome": "Cliente do Fluxo", "cidade": "Salvador",
                         "vendedor": "Vendedor X", "dataPagamento": "2026-08-01"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer clienteId = JsonPath.read(clienteJson, "$.id");

        // 2. Abre a pendência na Coelba
        String pendenciaJson = mvc.perform(post("/api/pendencias")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clienteId": %d, "tipo": "TROCA_TITULARIDADE",
                         "observacao": "Aguardando documento"}""".formatted(clienteId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ABERTA"))
                .andReturn().getResponse().getContentAsString();
        Integer pendenciaId = JsonPath.read(pendenciaJson, "$.id");

        // 2b. Resolver antes de consultar o débito é recusado: a analista precisa saber se o
        //     cliente deve, e a ausência de consulta não é "não deve", é "ninguém olhou".
        mvc.perform(post("/api/pendencias/%d/resolver".formatted(pendenciaId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DEBITO_NAO_CONSULTADO"));

        // 2c. Registra a consulta na agência virtual: sem débito de pendência, libera a resolução.
        mvc.perform(put("/api/debitos/cliente/%d".formatted(clienteId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"tipo": "PENDENCIA", "status": "QUITADO"}"""))
                .andExpect(status().isOk());

        // 3. Resolve — é aqui que a automação entre etapas deve disparar
        mvc.perform(post("/api/pendencias/%d/resolver".formatted(pendenciaId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"observacao": "Titularidade trocada"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVIDA"))
                .andExpect(jsonPath("$.resolvidoEm").isNotEmpty());

        // 4. O projeto nasceu sozinho, em RECEBIDO
        mvc.perform(get("/api/projetos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("clienteId", String.valueOf(clienteId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].status").value("RECEBIDO"))
                .andExpect(jsonPath("$.conteudo[0].tipoProjeto").value("PROJETO_INICIAL"))
                .andExpect(jsonPath("$.conteudo[0].clienteNome").value("Cliente do Fluxo"));

        // 5. E a auditoria registrou as duas transições da pendência
        mvc.perform(get("/api/pendencias/%d/historico".formatted(pendenciaId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].statusNovo").value("ABERTA"))
                .andExpect(jsonPath("$[1].statusAnterior").value("ABERTA"))
                .andExpect(jsonPath("$[1].statusNovo").value("RESOLVIDA"))
                .andExpect(jsonPath("$[1].usuarioId").value(usuarioCriadoId.intValue()));

        assertThat(projetoRepository.findByClienteId(Long.valueOf(clienteId))).hasSize(1);

        // 6. Resolver de novo é idempotente: responde 200, mas não duplica linha de histórico
        // nem cria um segundo projeto. Sem isso, um duplo clique distorceria as métricas.
        mvc.perform(post("/api/pendencias/%d/resolver".formatted(pendenciaId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(get("/api/pendencias/%d/historico".formatted(pendenciaId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(2));

        assertThat(projetoRepository.findByClienteId(Long.valueOf(clienteId))).hasSize(1);
    }

    @Test
    void aprovarProjetoQueNuncaFoiEncaminhadoRetorna409() throws Exception {
        String token = autenticar();

        String clienteJson = mvc.perform(post("/api/clientes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nome": "Cliente Transição"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer clienteId = JsonPath.read(clienteJson, "$.id");

        String projetoJson = mvc.perform(post("/api/projetos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clienteId": %d, "tipoProjeto": "AMPLIACAO"}""".formatted(clienteId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEBIDO"))
                .andReturn().getResponse().getContentAsString();
        Integer projetoId = JsonPath.read(projetoJson, "$.id");

        // A guarda que protege a métrica de tempo até aprovação do dashboard.
        mvc.perform(post("/api/projetos/%d/aprovar".formatted(projetoId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("TRANSICAO_INVALIDA"));
    }

    @Test
    void encaminharPreencheDataEncaminhadoQueODashboardUsa() throws Exception {
        String token = autenticar();

        String clienteJson = mvc.perform(post("/api/clientes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nome": "Cliente Encaminhado"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer clienteId = JsonPath.read(clienteJson, "$.id");

        String projetoJson = mvc.perform(post("/api/projetos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clienteId": %d, "tipoProjeto": "PROJETO_INICIAL"}""".formatted(clienteId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer projetoId = JsonPath.read(projetoJson, "$.id");

        // Encaminhar exige a consulta de débito de homologação — é o passo do projetista ao
        // receber o cliente, e é o que impede o projeto de ir à Coelba sem ninguém ter olhado.
        mvc.perform(put("/api/debitos/cliente/%d".formatted(clienteId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"tipo": "HOMOLOGACAO", "status": "QUITADO"}"""))
                .andExpect(status().isOk());

        mvc.perform(post("/api/projetos/%d/encaminhar".formatted(projetoId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"dataArt": "2026-08-20", "dataEncaminhado": "2026-08-25",
                         "numeroSolicitacao": "2026-COE-551234"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENCAMINHADO"))
                .andExpect(jsonPath("$.dataEncaminhado").value("2026-08-25"))
                .andExpect(jsonPath("$.dataArt").value("2026-08-20"))
                .andExpect(jsonPath("$.numeroSolicitacao").value("2026-COE-551234"));

        // O número é a chave de busca do retorno da Coelba: é por ele que o analista acha o
        // projeto quando o e-mail chega, e é o que a automação futura vai casar.
        mvc.perform(get("/api/projetos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("q", "551234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].id").value(projetoId));

        mvc.perform(post("/api/projetos/%d/aprovar".formatted(projetoId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROVADO"))
                .andExpect(jsonPath("$.dataAprovacao").isNotEmpty());
    }

    @Test
    void requisicaoSemTokenNaoPassa() throws Exception {
        mvc.perform(get("/api/pendencias"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NAO_AUTENTICADO"));
    }

    @Test
    void analistaNaoPodeApagarPendencia() throws Exception {
        String token = autenticar();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/pendencias/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
    }
}
