package com.conectsol.solarsync.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.conectsol.solarsync.common.AbstractIntegrationTest;
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.integracao.UsuarioIntegracao;
import com.jayway.jsonpath.JsonPath;

/**
 * Achado A-01 da auditoria de segurança (docs/security-audit), reproduzido ponta a ponta contra
 * a conta de integração de verdade — a semeada pela V13 e marcada pela V19.
 * <p>
 * A exploração era: GESTOR reativa a conta, define uma senha, dá-lhe um papel e entra por ela;
 * tudo o que fizesse depois ficava no histórico como "Integração automática". Cada passo tem de
 * falhar sozinho, porque bloquear só um deixaria os outros como degraus para a próxima brecha.
 */
@AutoConfigureMockMvc
class ContaDeSistemaHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String EMAIL_GESTOR = "gestor.conta-sistema@conectsol.com";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PapelRepository papelRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private HistoricoStatusRepository historicoStatusRepository;

    /** Os papéis são LAZY e o teste roda fora de transação: conferidos direto na tabela. */
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> criados = new ArrayList<>();
    private Long gestorId;
    private Long integracaoId;
    private String token;

    @BeforeEach
    void preparar() throws Exception {
        Usuario gestor = usuarioRepository.save(Usuario.builder()
                .nome("Gestor do Teste")
                .email(EMAIL_GESTOR)
                .ativo(true)
                .senhaHash(passwordEncoder.encode(SENHA))
                .papeis(Set.of(papelRepository.findByNome(NomePapel.GESTOR).orElseThrow()))
                .build());
        gestorId = gestor.getId();
        criados.add(gestorId);
        token = entrar(EMAIL_GESTOR, SENHA);

        integracaoId = usuarioRepository.findByEmail(UsuarioIntegracao.EMAIL).orElseThrow().getId();
    }

    /** Histórico antes dos usuários, pela FK de historico_status.usuario_id. */
    @AfterEach
    void limpar() {
        historicoStatusRepository.deleteAll();
        usuarioRepository.findById(integracaoId).ifPresent(conta -> {
            conta.setSenhaHash(null);
            conta.setAtivo(false);
            usuarioRepository.save(conta);
        });
        criados.forEach(usuarioRepository::deleteById);
        criados.clear();
    }

    @Test
    void aV19MarcaAContaDeIntegracaoComoContaDeSistema() {
        Usuario conta = usuarioRepository.findById(integracaoId).orElseThrow();

        assertThat(conta.isContaSistema()).isTrue();
        assertThat(conta.isAtivo()).isFalse();
        assertThat(conta.getSenhaHash()).isNull();
        assertThat(papeisDaIntegracao()).isZero();
    }

    @Test
    void gestorNaoReativaNemDaSenhaNemPapelAContaDeSistema() throws Exception {
        autenticada(post("/api/usuarios/{id}/ativar", integracaoId), "")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));

        autenticada(post("/api/usuarios/{id}/senha", integracaoId), """
                {"senha": "senha-do-atacante-1"}""")
                .andExpect(status().isForbidden());

        autenticada(put("/api/usuarios/{id}", integracaoId), """
                {"nome": "Integração automática", "email": "%s", "papeis": ["GESTOR"]}"""
                .formatted(UsuarioIntegracao.EMAIL))
                .andExpect(status().isForbidden());

        Usuario conta = usuarioRepository.findById(integracaoId).orElseThrow();
        assertThat(conta.isAtivo()).isFalse();
        assertThat(conta.getSenhaHash()).isNull();
        assertThat(papeisDaIntegracao()).isZero();
    }

    /** Mesmo que alguém grave uma senha direto no banco, a conta não entra. */
    @Test
    void contaDeSistemaNaoEntraNemComSenhaGravadaNoBanco() throws Exception {
        Usuario conta = usuarioRepository.findById(integracaoId).orElseThrow();
        conta.setSenhaHash(passwordEncoder.encode(SENHA));
        conta.setAtivo(true);
        usuarioRepository.save(conta);

        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "senha": "%s"}""".formatted(UsuarioIntegracao.EMAIL, SENHA)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contaDeSistemaNaoApareceNaTelaDeUsuarios() throws Exception {
        autenticada(get("/api/usuarios"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", not(hasItem(UsuarioIntegracao.EMAIL))))
                .andExpect(jsonPath("$[*].email", hasItem(EMAIL_GESTOR)));
    }

    /** A outra metade do achado: a gestão de usuários não deixava rastro nenhum. */
    @Test
    void gestaoDeUsuariosDeixaTrilhaComAutor() throws Exception {
        String criado = autenticada(post("/api/usuarios"), """
                {"nome": "Nova Analista", "email": "nova.conta-sistema@conectsol.com",
                 "papeis": ["ANALISTA"], "senha": "senha-provisoria-1"}""")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long alvo = ((Number) JsonPath.read(criado, "$.id")).longValue();
        criados.add(0, alvo);

        autenticada(post("/api/usuarios/{id}/senha", alvo), """
                {"senha": "outra-senha-123"}""").andExpect(status().isOk());
        autenticada(put("/api/usuarios/{id}", alvo), """
                {"nome": "Nova Analista", "email": "nova.conta-sistema@conectsol.com",
                 "papeis": ["GESTOR"]}""").andExpect(status().isOk());
        autenticada(post("/api/usuarios/{id}/desativar", alvo), "").andExpect(status().isOk());
        // Duplo clique: não vira uma segunda linha.
        autenticada(post("/api/usuarios/{id}/desativar", alvo), "").andExpect(status().isOk());

        autenticada(get("/api/usuarios/{id}/historico", alvo), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].statusNovo").value("ANALISTA"))
                .andExpect(jsonPath("$[1].statusNovo").value("SENHA_DEFINIDA"))
                .andExpect(jsonPath("$[2].statusAnterior").value("ANALISTA"))
                .andExpect(jsonPath("$[2].statusNovo").value("GESTOR"))
                .andExpect(jsonPath("$[3].statusNovo").value("INATIVO"))
                .andExpect(jsonPath("$[*].usuarioId",
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is(gestorId.intValue()))));
    }

    private int papeisDaIntegracao() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM usuario_papel WHERE usuario_id = ?",
                Integer.class, integracaoId);
    }

    private String entrar(String email, String senha) throws Exception {
        String login = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "senha": "%s"}""".formatted(email, senha)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(login, "$.accessToken");
    }

    private ResultActions autenticada(MockHttpServletRequestBuilder builder, String corpo)
            throws Exception {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (!corpo.isEmpty()) {
            builder.contentType(MediaType.APPLICATION_JSON).content(corpo);
        }
        return mvc.perform(builder);
    }
}
