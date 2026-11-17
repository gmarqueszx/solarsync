package com.conectsol.solarsync.auditoria;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.hamcrest.Matchers;
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

import com.conectsol.solarsync.auth.NomePapel;
import com.conectsol.solarsync.auth.PapelRepository;
import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.common.AbstractIntegrationTest;
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.jayway.jsonpath.JsonPath;

/**
 * O log de atividades registra quem fez o quê, com o antes/depois de cada campo — e só quem
 * gerencia a equipe o enxerga.
 */
@AutoConfigureMockMvc
class LogAtividadeHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired private MockMvc mvc;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PapelRepository papelRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ClienteRepository clienteRepository;
    @Autowired private ProjetoRepository projetoRepository;
    @Autowired private HistoricoStatusRepository historicoStatusRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long analistaId;
    private Long gestorId;
    private String tokenAnalista;
    private String tokenGestor;

    @BeforeEach
    void preparar() throws Exception {
        analistaId = criarUsuario("Larissa do Log", "larissa.log@conectsol.com", NomePapel.ANALISTA);
        gestorId = criarUsuario("Igor do Log", "igor.log@conectsol.com", NomePapel.GESTOR);
        tokenAnalista = login("larissa.log@conectsol.com");
        tokenGestor = login("igor.log@conectsol.com");
        jdbc.update("DELETE FROM log_atividade");
    }

    @AfterEach
    void limpar() {
        historicoStatusRepository.deleteAll();
        projetoRepository.deleteAll();
        clienteRepository.deleteAll();
        usuarioRepository.deleteById(analistaId);
        usuarioRepository.deleteById(gestorId);
        jdbc.update("DELETE FROM log_atividade");
    }

    @Test
    void criacaoEEdicaoFicamNoLogComAutorEAntesDepois() throws Exception {
        String criado = mvc.perform(post("/api/clientes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAnalista)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nome": "Maria Auditada", "cidade": "Salvador", "telefone": "71 1111-1111"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer clienteId = JsonPath.read(criado, "$.id");

        mvc.perform(put("/api/clientes/" + clienteId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAnalista)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nome": "Maria Auditada", "cidade": "Salvador", "telefone": "71 2222-2222"}"""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/logs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenGestor)
                .param("entidadeTipo", "CLIENTE")
                .param("entidadeId", clienteId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(2))
                // Mais recente primeiro: a edição, só com o campo que mudou.
                .andExpect(jsonPath("$.conteudo[0].acao").value("EDICAO"))
                .andExpect(jsonPath("$.conteudo[0].usuarioId").value(analistaId))
                .andExpect(jsonPath("$.conteudo[0].usuarioNome").value("Larissa do Log"))
                .andExpect(jsonPath("$.conteudo[0].descricao").value("Maria Auditada"))
                .andExpect(jsonPath("$.conteudo[0].alteracoes.length()").value(1))
                .andExpect(jsonPath("$.conteudo[0].alteracoes[0].campo").value("telefone"))
                .andExpect(jsonPath("$.conteudo[0].alteracoes[0].anterior").value("71 1111-1111"))
                .andExpect(jsonPath("$.conteudo[0].alteracoes[0].novo").value("71 2222-2222"))
                .andExpect(jsonPath("$.conteudo[1].acao").value("CRIACAO"))
                .andExpect(jsonPath("$.conteudo[1].alteracoes[*].campo",
                        Matchers.hasItems("nome", "cidade", "telefone", "statusTriagem")));
    }

    @Test
    void mudancaDeStatusApareceComoEdicaoDoCampo() throws Exception {
        String criado = mvc.perform(post("/api/clientes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAnalista)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nome": "José Sem Pendência"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer clienteId = JsonPath.read(criado, "$.id");

        mvc.perform(post("/api/clientes/" + clienteId + "/sem-pendencia")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAnalista))
                .andExpect(status().isOk());

        mvc.perform(get("/api/logs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenGestor)
                .param("entidadeTipo", "CLIENTE")
                .param("acao", "EDICAO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo[0].alteracoes[0].campo").value("statusTriagem"))
                .andExpect(jsonPath("$.conteudo[0].alteracoes[0].anterior")
                        .value("AGUARDANDO_VERIFICACAO"))
                .andExpect(jsonPath("$.conteudo[0].alteracoes[0].novo").value("SEM_PENDENCIA"));

        // O projeto que nasce sozinho fica atribuído a quem marcou o cliente.
        mvc.perform(get("/api/logs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenGestor)
                .param("entidadeTipo", "PROJETO")
                .param("usuarioId", analistaId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].acao").value("CRIACAO"))
                .andExpect(jsonPath("$.conteudo[0].descricao").value("José Sem Pendência"));
    }

    @Test
    void senhaNuncaVaiParaOLog() throws Exception {
        mvc.perform(post("/api/usuarios/" + analistaId + "/senha")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenGestor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"senha": "outra-senha-forte-456"}"""))
                .andExpect(status().is2xxSuccessful());

        String corpo = mvc.perform(get("/api/logs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenGestor)
                .param("entidadeTipo", "USUARIO")
                .param("entidadeId", analistaId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo[0].usuarioId").value(gestorId))
                .andExpect(jsonPath("$.conteudo[0].alteracoes[0].campo").value("senha"))
                .andExpect(jsonPath("$.conteudo[0].alteracoes[0].novo").value("redefinida"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(corpo).doesNotContain("$2a$");
    }

    @Test
    void analistaNaoVeOLog() throws Exception {
        mvc.perform(get("/api/logs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAnalista))
                .andExpect(status().isForbidden());
    }

    private Long criarUsuario(String nome, String email, NomePapel papel) {
        return usuarioRepository.save(Usuario.builder()
                .nome(nome).email(email).ativo(true)
                .senhaHash(passwordEncoder.encode(SENHA))
                .papeis(Set.of(papelRepository.findByNome(papel).orElseThrow()))
                .build()).getId();
    }

    private String login(String email) throws Exception {
        String resposta = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "senha": "%s"}""".formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resposta, "$.accessToken");
    }
}
