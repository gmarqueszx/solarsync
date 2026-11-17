package com.conectsol.solarsync.projeto;

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
import com.jayway.jsonpath.JsonPath;

/**
 * O número de solicitação não pode ir parar em dois projetos pela operação normal (16/11/2026).
 * <p>
 * O caso real: o mesmo cliente cadastrado duas vezes (uma vinda do CRM, outra à mão), os dois
 * projetos encaminhados com o número 2611050960, e a aprovação da Coelba registrada como
 * {@code AMBIGUO} — nada aplicado, e o projeto parado em {@code ENCAMINHADO} cinco dias depois de
 * aprovado.
 */
@AutoConfigureMockMvc
class NumeroSolicitacaoDuplicadoHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String EMAIL = "analista.duplicado@conectsol.com";
    private static final String NUMERO = """
            {"numeroSolicitacao": "2611050960"}""";

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
    private DebitoRepository debitoRepository;

    @Autowired
    private HistoricoStatusRepository historicoStatusRepository;

    private Long usuarioCriadoId;
    private String token;
    private Integer primeiroProjetoId;
    private Integer segundoProjetoId;

    @BeforeEach
    void prepararCenario() throws Exception {
        Usuario analista = Usuario.builder()
                .nome("Analista do Número Duplicado")
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

        // O mesmo cliente, cadastrado duas vezes — com e sem til, como aconteceu.
        primeiroProjetoId = projetoProntoParaEnvio("JOÃO BATISTA GAMA SILVA");
        segundoProjetoId = projetoProntoParaEnvio("JOAO BATISTA GAMA SILVA");
    }

    @AfterEach
    void limpar() {
        historicoStatusRepository.deleteAll();
        debitoRepository.deleteAll();
        projetoRepository.deleteAll();
        if (usuarioCriadoId != null) {
            usuarioRepository.deleteById(usuarioCriadoId);
            usuarioCriadoId = null;
        }
        clienteRepository.deleteAll();
    }

    @Test
    void encaminharComNumeroDeOutroProjetoEhRecusado() throws Exception {
        requisicao(post("/api/projetos/%d/encaminhar".formatted(primeiroProjetoId)), NUMERO)
                .andExpect(status().isOk());

        requisicao(post("/api/projetos/%d/encaminhar".formatted(segundoProjetoId)), NUMERO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("NUMERO_SOLICITACAO_DUPLICADO"));

        Projeto recusado = projetoRepository.findById(segundoProjetoId.longValue()).orElseThrow();
        assertThat(recusado.getStatus()).isEqualTo(StatusProjeto.RECEBIDO);
        assertThat(recusado.getNumeroSolicitacao()).isNull();
    }

    /** O próprio projeto não conta como "outro": reenviar com o mesmo número é o caso normal. */
    @Test
    void reenviarOMesmoProjetoComOMesmoNumeroContinuaValendo() throws Exception {
        requisicao(post("/api/projetos/%d/encaminhar".formatted(primeiroProjetoId)), NUMERO)
                .andExpect(status().isOk());
        requisicao(post("/api/projetos/%d/reprovar".formatted(primeiroProjetoId)), """
                {"motivo": "Diagrama unifilar divergente"}""")
                .andExpect(status().isOk());

        requisicao(post("/api/projetos/%d/reencaminhar".formatted(primeiroProjetoId)), NUMERO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REENCAMINHADO"));
    }

    /** Sem isto, a edição do projeto seria o contorno da guarda do envio. */
    @Test
    void corrigirONumeroPeloPutParaOdeOutroProjetoEhRecusado() throws Exception {
        requisicao(post("/api/projetos/%d/encaminhar".formatted(primeiroProjetoId)), NUMERO)
                .andExpect(status().isOk());

        requisicao(put("/api/projetos/" + segundoProjetoId), """
                {"tipoProjeto": "PROJETO_INICIAL", "numeroSolicitacao": "2611050960"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("NUMERO_SOLICITACAO_DUPLICADO"));
    }

    /** Cliente, projeto em RECEBIDO e a consulta de débito de homologação que o envio exige. */
    private Integer projetoProntoParaEnvio(String nomeCliente) throws Exception {
        String cliente = requisicao(post("/api/clientes"), """
                {"nome": "%s", "cidade": "Ituaçu"}""".formatted(nomeCliente))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer clienteId = JsonPath.read(cliente, "$.id");

        requisicao(put("/api/debitos/cliente/" + clienteId), """
                {"tipo": "HOMOLOGACAO", "status": "QUITADO"}""")
                .andExpect(status().isOk());

        String projeto = requisicao(post("/api/projetos"), """
                {"clienteId": %d, "tipoProjeto": "PROJETO_INICIAL"}""".formatted(clienteId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(projeto, "$.id");
    }

    private ResultActions requisicao(MockHttpServletRequestBuilder builder, String corpo)
            throws Exception {
        return mvc.perform(builder
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }
}
