package com.conectsol.solarsync.debito;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.conectsol.solarsync.common.FusoDaOperacao;
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.pendencia.PendenciaRepository;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.jayway.jsonpath.JsonPath;

/**
 * O débito futuro (22/09/2026): o cliente está quitado hoje e a próxima conta vence amanhã.
 * Encaminhar nessa véspera faz a Coelba analisar o projeto já com débito em aberto, e o projeto
 * volta reprovado.
 * <p>
 * É a terceira recusa do envio, ao lado de {@code DEBITO_NAO_CONSULTADO} e
 * {@code CLIENTE_COM_DEBITO}, e tem código próprio porque pede uma ação diferente das outras
 * duas: esperar, não cobrar — não há o que cobrar, a conta ainda nem venceu.
 */
@AutoConfigureMockMvc
class ProximoDebitoBloqueiaEnvioHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String EMAIL = "analista.proximo@conectsol.com";
    private static final String NUMERO_VALIDO = """
            {"numeroSolicitacao": "2026-COE-909090"}""";

    /** O "hoje" da operação, e não o do servidor — a mesma disciplina que a guarda aplica. */
    private static final LocalDate HOJE = FusoDaOperacao.hoje();

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
    private Integer clienteId;
    private Integer projetoId;

    @BeforeEach
    void prepararCenario() throws Exception {
        Usuario analista = Usuario.builder()
                .nome("Analista do Próximo Débito")
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

        String cliente = requisicao(post("/api/clientes"), """
                {"nome": "Cliente com Conta a Vencer", "cidade": "Brumado"}""")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        clienteId = JsonPath.read(cliente, "$.id");

        String projeto = requisicao(post("/api/projetos"), """
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

    /** O caso normal: quitado e sem próxima conta conhecida, o envio acontece. */
    @Test
    void semProximoDebitoOEnvioAcontece() throws Exception {
        quitar(null);

        requisicao(post("/api/projetos/%d/encaminhar".formatted(projetoId)), NUMERO_VALIDO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENCAMINHADO"));
    }

    /** Uma data longe não trava nada: o projeto vai e volta antes de a conta vencer. */
    @Test
    void proximoDebitoDistanteNaoBloqueia() throws Exception {
        quitar(HOJE.plusDays(20));

        requisicao(post("/api/projetos/%d/encaminhar".formatted(projetoId)), NUMERO_VALIDO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENCAMINHADO"));
    }

    /**
     * A borda que o requisito descreve: "próximo débito vence em 23/09, projeto sendo encaminhado
     * em 22/09". Um dia de folga já é pouco demais.
     */
    @Test
    void proximoDebitoAmanhaBloqueiaOEnvio() throws Exception {
        quitar(HOJE.plusDays(1));

        requisicao(post("/api/projetos/%d/encaminhar".formatted(projetoId)), NUMERO_VALIDO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PROXIMO_DEBITO_A_VENCER"));

        // O projeto não muda de estado: a recusa acontece antes da transição, como as outras
        // guardas de envio. O cliente travado continua visível onde estava.
        requisicao(post("/api/projetos/%d/aguardar-envio".formatted(projetoId)), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_ENVIO"));
    }

    /** Dois dias ainda cabem — a folga exigida é de um dia, não de uma semana. */
    @Test
    void proximoDebitoEmDoisDiasAindaPermiteOEnvio() throws Exception {
        quitar(HOJE.plusDays(2));

        requisicao(post("/api/projetos/%d/encaminhar".formatted(projetoId)), NUMERO_VALIDO)
                .andExpect(status().isOk());
    }

    /**
     * Data já vencida também barra, e é o caso mais comum: a data informada na última consulta
     * costuma ter passado quando alguém tenta enviar semanas depois. Se ela passou e ninguém
     * reconsultou, a situação real do cliente é desconhecida — enviar seria apostar.
     */
    @Test
    void proximoDebitoJaVencidoBloqueia() throws Exception {
        quitar(HOJE.minusDays(3));

        requisicao(post("/api/projetos/%d/encaminhar".formatted(projetoId)), NUMERO_VALIDO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PROXIMO_DEBITO_A_VENCER"));
    }

    /** O reenvio passa pela mesma guarda: é outra ida à Coelba, com o mesmo risco. */
    @Test
    void oReencaminhamentoPassaPelaMesmaGuarda() throws Exception {
        quitar(null);
        requisicao(post("/api/projetos/%d/encaminhar".formatted(projetoId)), NUMERO_VALIDO)
                .andExpect(status().isOk());
        requisicao(post("/api/projetos/%d/reprovar".formatted(projetoId)), """
                {"motivo": "Documentação incompleta"}""")
                .andExpect(status().isOk());

        quitar(HOJE.plusDays(1));

        requisicao(post("/api/projetos/%d/reencaminhar".formatted(projetoId)), NUMERO_VALIDO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PROXIMO_DEBITO_A_VENCER"));
    }

    /**
     * A data só acompanha a quitação. Com o débito ativo não existe "próxima conta" a esperar —
     * existe a atual, e é ela que barra o envio; guardar as duas daria dois códigos de erro
     * diferentes para o mesmo cliente parado pelo mesmo motivo.
     */
    @Test
    void proximoVencimentoEhIgnoradoQuandoODebitoEstaAtivo() throws Exception {
        requisicao(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "HOMOLOGACAO", "status": "ATIVO", "proximoVencimento": "%s"}"""
                .formatted(HOJE.plusDays(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proximoVencimento").doesNotExist());

        requisicao(post("/api/projetos/%d/encaminhar".formatted(projetoId)), NUMERO_VALIDO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_COM_DEBITO"));
    }

    /**
     * Reconsultar substitui a data — é assim que se descobre que a conta mudou de vencimento, ou
     * que não há mais nenhuma à vista. Sem isso, um cliente ficaria travado para sempre por uma
     * data antiga.
     */
    @Test
    void reconsultarSubstituiAData() throws Exception {
        quitar(HOJE.plusDays(1));
        requisicao(post("/api/projetos/%d/encaminhar".formatted(projetoId)), NUMERO_VALIDO)
                .andExpect(status().isConflict());

        quitar(null);
        requisicao(post("/api/projetos/%d/encaminhar".formatted(projetoId)), NUMERO_VALIDO)
                .andExpect(status().isOk());
    }

    /** Registra a consulta de homologação como QUITADO, com (ou sem) a data da próxima conta. */
    private void quitar(LocalDate proximoVencimento) throws Exception {
        String corpo = proximoVencimento == null
                ? """
                        {"tipo": "HOMOLOGACAO", "status": "QUITADO"}"""
                : """
                        {"tipo": "HOMOLOGACAO", "status": "QUITADO", "proximoVencimento": "%s"}"""
                        .formatted(proximoVencimento);

        requisicao(put("/api/debitos/cliente/" + clienteId), corpo)
                .andExpect(status().isOk())
                .andExpect(proximoVencimento == null
                        ? jsonPath("$.proximoVencimento").doesNotExist()
                        : jsonPath("$.proximoVencimento").value(proximoVencimento.toString()));
    }

    private ResultActions requisicao(MockHttpServletRequestBuilder builder, String corpo)
            throws Exception {
        return mvc.perform(builder
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }
}
