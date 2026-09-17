package com.conectsol.solarsync.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.conectsol.solarsync.auth.config.SecurityConfig;
import com.conectsol.solarsync.auth.dto.UsuarioResponse;
import com.conectsol.solarsync.common.config.WebMvcConfig;
import com.conectsol.solarsync.common.security.UsuarioAutenticadoArgumentResolver;
import com.conectsol.solarsync.common.web.ApiExceptionHandler;

/**
 * A gestão de usuários virou trabalho de operação quando o login Google saiu (16/09/2026): sem
 * auto-cadastro, esta é a <b>única</b> porta de entrada de gente no sistema, e ficar só com o
 * ADMINISTRADOR faria toda contratação esperar por uma pessoa.
 * <p>
 * O que este teste protege é a fronteira que a abertura criou: GESTOR cadastra a equipe, mas
 * <b>não</b> cria nem altera ADMINISTRADOR, e continua sem poder excluir ninguém. Sem isso, um
 * gestor poderia criar uma conta de administrador para si e a linha "Apagar: só ADMIN" da
 * matriz RBAC viraria decoração.
 */
@WebMvcTest(controllers = UsuarioController.class)
@Import({ SecurityConfig.class, WebMvcConfig.class, UsuarioAutenticadoArgumentResolver.class,
        ApiExceptionHandler.class })
class UsuarioControllerRbacTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UsuarioService usuarioService;

    /** Satisfaz a autoconfiguração do resource server sem precisar de chave real. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor comPapel(String papel) {
        return jwt()
                .jwt(token -> token
                        .subject("7")
                        .claim("email", "usuario@conectsol.com")
                        .claim("nome", "Usuário de Teste")
                        .claim("papeis", List.of(papel)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + papel));
    }

    private static UsuarioResponse respostaFalsa() {
        return new UsuarioResponse(3L, "Nova Analista", "nova@conectsol.com", true, true,
                List.of(NomePapel.ANALISTA));
    }

    private static final String CORPO_NOVO = """
            {"nome": "Nova Analista", "email": "nova@conectsol.com", \
            "papeis": ["ANALISTA"], "senha": "senha-provisoria-1"}""";

    @ParameterizedTest
    @ValueSource(strings = { "GESTOR", "ADMINISTRADOR" })
    void gestorEAdministradorListamUsuarios(String papel) throws Exception {
        when(usuarioService.listar()).thenReturn(List.of(respostaFalsa()));

        mvc.perform(get("/api/usuarios").with(comPapel(papel)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("nova@conectsol.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "GESTOR", "ADMINISTRADOR" })
    void gestorEAdministradorCadastramUsuario(String papel) throws Exception {
        when(usuarioService.criar(any(), any())).thenReturn(respostaFalsa());

        mvc.perform(post("/api/usuarios")
                .with(comPapel(papel))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CORPO_NOVO))
                .andExpect(status().isCreated());
    }

    @Test
    void analistaNaoAlcancaAGestaoDeUsuarios() throws Exception {
        mvc.perform(get("/api/usuarios").with(comPapel("ANALISTA")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));

        mvc.perform(post("/api/usuarios")
                .with(comPapel("ANALISTA"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CORPO_NOVO))
                .andExpect(status().isForbidden());

        verifyNoInteractions(usuarioService);
    }

    /** O seletor de responsável das telas sai daqui, então continua liberado a todos. */
    @Test
    void analistaAindaUsaOLookupParaOsSeletores() throws Exception {
        when(usuarioService.lookup(true)).thenReturn(List.of());

        mvc.perform(get("/api/usuarios/lookup").with(comPapel("ANALISTA")))
                .andExpect(status().isOk());
    }

    /**
     * Excluir continua fora do alcance do gestor: apagar quem já aparece no histórico destrói a
     * auditoria que sustenta o dashboard, e o caminho previsto é desativar.
     */
    @Test
    void gestorNaoExcluiUsuario() throws Exception {
        mvc.perform(delete("/api/usuarios/3").with(comPapel("GESTOR")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(usuarioService);
    }

    @Test
    void administradorExcluiUsuario() throws Exception {
        mvc.perform(delete("/api/usuarios/3").with(comPapel("ADMINISTRADOR")))
                .andExpect(status().isNoContent());
    }

    @Test
    void senhaEObrigatoriaNoCadastro() throws Exception {
        // Sem login federado, usuário sem senha é conta que não entra por caminho nenhum.
        mvc.perform(post("/api/usuarios")
                .with(comPapel("ADMINISTRADOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nome": "Sem Senha", "email": "sem@conectsol.com", \
                        "papeis": ["ANALISTA"]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACAO"));

        verifyNoInteractions(usuarioService);
    }

    /** Trocar senha é endpoint próprio: o PUT do cadastro não aceita o campo. */
    @Test
    void gestorRedefineSenha() throws Exception {
        when(usuarioService.definirSenha(anyLong(), any(), any())).thenReturn(respostaFalsa());

        mvc.perform(post("/api/usuarios/3/senha")
                .with(comPapel("GESTOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"senha": "outra-senha-123"}"""))
                .andExpect(status().isOk());
    }
}
