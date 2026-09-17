package com.conectsol.solarsync.debito;

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
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.pendencia.PendenciaRepository;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.jayway.jsonpath.JsonPath;

/**
 * A regra da etapa 2 do fluxo, ponta a ponta: com débito ativo o projeto existe mas não vai
 * para a Coelba; quitado, vai.
 * <p>
 * Sem {@code @Transactional} e com limpeza no {@code @AfterEach}, seguindo o padrão dos outros
 * testes de integração (há listener em AFTER_COMMIT no fluxo).
 */
@AutoConfigureMockMvc
class DebitoBloqueiaEnvioHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String EMAIL = "analista.debito@conectsol.com";

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
    private Integer clienteId;
    private Integer projetoId;

    @BeforeEach
    void prepararCenario() throws Exception {
        Usuario analista = Usuario.builder()
                .nome("Analista do Débito")
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

        String cliente = requisicaoAutenticada(post("/api/clientes"), """
                {"nome": "Cliente Devedor", "cidade": "Salvador"}""")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        clienteId = JsonPath.read(cliente, "$.id");

        String projeto = requisicaoAutenticada(post("/api/projetos"), """
                {"clienteId": %d, "tipoProjeto": "PROJETO_INICIAL"}""".formatted(clienteId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projetoId = JsonPath.read(projeto, "$.id");
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

    private org.springframework.test.web.servlet.ResultActions requisicaoAutenticada(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String corpo) throws Exception {

        return mvc.perform(builder
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    @Test
    void debitoAtivoBloqueiaOEnvioEQuitarLibera() throws Exception {
        assertThat(debitoRepository.findByClienteIdAndTipo(
                Long.valueOf(clienteId), TipoDebito.HOMOLOGACAO)).isEmpty();

        // Sem consulta de homologação registrada, encaminhar é recusado: ninguém olhou se este
        // cliente deve, e "não olhou" não é "não deve".
        requisicaoAutenticada(post("/api/projetos/%d/encaminhar".formatted(projetoId)), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DEBITO_NAO_CONSULTADO"));

        requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "HOMOLOGACAO", "status": "ATIVO"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("HOMOLOGACAO"))
                .andExpect(jsonPath("$.status").value("ATIVO"))
                .andExpect(jsonPath("$.cliente.nome").value("Cliente Devedor"))
                .andExpect(jsonPath("$.ultimaConsultaEm").isNotEmpty())
                .andExpect(jsonPath("$.detectadoEm").isNotEmpty())
                .andExpect(jsonPath("$.diasParado").value(0))
                .andExpect(jsonPath("$.consultadoPor.id").value(usuarioCriadoId.intValue()));

        // O projeto continua existindo e visível — o cliente travado não desaparece da tela.
        mvc.perform(get("/api/projetos/" + projetoId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEBIDO"));

        requisicaoAutenticada(post("/api/projetos/%d/encaminhar".formatted(projetoId)), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_COM_DEBITO"));

        requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "HOMOLOGACAO", "status": "QUITADO"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUITADO"))
                .andExpect(jsonPath("$.quitadoEm").isNotEmpty())
                // Quitado não está parando ninguém: nulo, e não zero.
                .andExpect(jsonPath("$.diasParado").doesNotExist());

        requisicaoAutenticada(post("/api/projetos/%d/encaminhar".formatted(projetoId)), """
                {"numeroSolicitacao": "2026-COE-777001"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENCAMINHADO"))
                .andExpect(jsonPath("$.dataEncaminhado").isNotEmpty())
                .andExpect(jsonPath("$.numeroSolicitacao").value("2026-COE-777001"));
    }

    /**
     * O ponto do modelo de dois tipos: um débito que trava a pendência não pode travar o envio
     * do projeto, e vice-versa. Antes havia um registro só, então quitar para uma etapa
     * destravava a outra sem ninguém ter olhado.
     */
    @Test
    void debitoDePendenciaNaoBloqueiaOEnvioDoProjeto() throws Exception {
        requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "PENDENCIA", "status": "ATIVO"}""")
                .andExpect(status().isOk());

        // Continua faltando a consulta de homologação — a de pendência não responde por ela.
        requisicaoAutenticada(post("/api/projetos/%d/encaminhar".formatted(projetoId)), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DEBITO_NAO_CONSULTADO"));

        requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "HOMOLOGACAO", "status": "QUITADO"}""")
                .andExpect(status().isOk());

        // Com a homologação quitada o envio passa, ainda que a pendência siga travada.
        requisicaoAutenticada(post("/api/projetos/%d/encaminhar".formatted(projetoId)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENCAMINHADO"));
    }

    /** As duas situações do cliente numa consulta só, que é o que a tela de detalhe mostra. */
    @Test
    void buscarPorClienteDevolveOsDoisTipos() throws Exception {
        requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "PENDENCIA", "status": "QUITADO"}""")
                .andExpect(status().isOk());
        requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "HOMOLOGACAO", "status": "ATIVO"}""")
                .andExpect(status().isOk());

        mvc.perform(get("/api/debitos/cliente/" + clienteId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tipo").value("PENDENCIA"))
                .andExpect(jsonPath("$[1].tipo").value("HOMOLOGACAO"));
    }

    @Test
    void historicoDoDebitoPermiteMedirOTempoParado() throws Exception {
        String debito = requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "HOMOLOGACAO", "status": "ATIVO"}""")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Integer debitoId = JsonPath.read(debito, "$.id");

        requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "HOMOLOGACAO", "status": "QUITADO"}""")
                .andExpect(status().isOk());

        // As duas pontas que a métrica "tempo médio parado por débito" subtrai.
        mvc.perform(get("/api/debitos/%d/historico".formatted(debitoId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].statusAnterior").doesNotExist())
                .andExpect(jsonPath("$[0].statusNovo").value("ATIVO"))
                .andExpect(jsonPath("$[0].ocorridoEm").isNotEmpty())
                .andExpect(jsonPath("$[1].statusAnterior").value("ATIVO"))
                .andExpect(jsonPath("$[1].statusNovo").value("QUITADO"))
                .andExpect(jsonPath("$[1].usuarioId").value(usuarioCriadoId.intValue()));
    }

    @Test
    void reconsultarAMesmaSituacaoAtualizaADataSemPoluirOHistorico() throws Exception {
        String primeira = requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "HOMOLOGACAO", "status": "ATIVO",
                 "consultadoEm": "2026-08-01T10:00:00Z"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ultimaConsultaEm").value("2026-08-01T10:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        Integer debitoId = JsonPath.read(primeira, "$.id");

        requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "HOMOLOGACAO", "status": "ATIVO",
                 "consultadoEm": "2026-08-20T10:00:00Z"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ultimaConsultaEm").value("2026-08-20T10:00:00Z"))
                // Reconsultar não reinicia o relógio: o cliente está parado desde a detecção.
                .andExpect(jsonPath("$.detectadoEm").value("2026-08-01T10:00:00Z"));

        // Uma linha só: senão o tempo parado por débito seria recontado a cada consulta.
        mvc.perform(get("/api/debitos/%d/historico".formatted(debitoId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void listagemFiltraQuemEstaTravadoPorDebitoEPorEtapa() throws Exception {
        requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "HOMOLOGACAO", "status": "ATIVO"}""")
                .andExpect(status().isOk());
        requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "PENDENCIA", "status": "QUITADO"}""")
                .andExpect(status().isOk());

        mvc.perform(get("/api/debitos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("status", "ATIVO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].cliente.nome").value("Cliente Devedor"));

        // As duas abas da tela: cada uma responde por uma etapa do fluxo.
        mvc.perform(get("/api/debitos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("tipo", "PENDENCIA")
                .param("status", "ATIVO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(0));

        mvc.perform(get("/api/debitos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("tipo", "HOMOLOGACAO")
                .param("status", "ATIVO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1));

        // Fila do financeiro: recém-detectado não entra no recorte de "parado há tempo demais".
        mvc.perform(get("/api/debitos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("paradoHaMaisDeDias", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(0));
    }

    @Test
    void analistaNaoPodeExcluirRegistroDeDebito() throws Exception {
        String debito = requisicaoAutenticada(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "HOMOLOGACAO", "status": "ATIVO"}""")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Integer debitoId = JsonPath.read(debito, "$.id");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/debitos/" + debitoId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
    }
}
