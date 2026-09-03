package com.conectsol.solarsync.pendencia;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.conectsol.solarsync.auth.config.SecurityConfig;
import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.common.config.WebMvcConfig;
import com.conectsol.solarsync.common.security.UsuarioAutenticadoArgumentResolver;
import com.conectsol.solarsync.common.web.ApiExceptionHandler;
import com.conectsol.solarsync.historico.HistoricoStatusService;

/**
 * Prova a matriz RBAC da seção 4 do CLAUDE.md endpoint por endpoint.
 * <p>
 * Este teste também é a rede que pega a regressão silenciosa mais perigosa da configuração de
 * segurança: se o {@code JwtGrantedAuthoritiesConverter} não estivesse lendo o claim
 * {@code papeis} com prefixo {@code ROLE_}, <b>todo</b> {@code hasRole} negaria — e sem um
 * teste como este, nada acusaria.
 */
@WebMvcTest(controllers = PendenciaController.class)
@Import({ SecurityConfig.class, WebMvcConfig.class, UsuarioAutenticadoArgumentResolver.class,
        ApiExceptionHandler.class })
class PendenciaControllerRbacTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PendenciaService pendenciaService;

    @MockitoBean
    private HistoricoStatusService historicoStatusService;

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

    private static Pendencia pendenciaFalsa() {
        Cliente cliente = Cliente.builder().nome("Cliente").build();
        cliente.setId(2L);

        Pendencia pendencia = Pendencia.builder()
                .cliente(cliente)
                .tipo(TipoPendencia.LIGACAO_NOVA)
                .status(StatusPendencia.ABERTA)
                .solicitadoEm(Instant.now())
                .build();
        pendencia.setId(1L);
        return pendencia;
    }

    @ParameterizedTest
    @ValueSource(strings = { "ANALISTA", "GESTOR", "ADMINISTRADOR" })
    void todosOsPapeisPodemLer(String papel) throws Exception {
        Page<Pendencia> pagina = new PageImpl<>(List.of(pendenciaFalsa()), Pageable.ofSize(20), 1);
        when(pendenciaService.listar(any(), any())).thenReturn(pagina);

        mvc.perform(get("/api/pendencias").with(comPapel(papel)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo[0].clienteNome").value("Cliente"))
                .andExpect(jsonPath("$.totalElementos").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = { "ANALISTA", "GESTOR", "ADMINISTRADOR" })
    void todosOsPapeisPodemResolverPendencia(String papel) throws Exception {
        when(pendenciaService.atualizarStatus(eq(1L), eq(StatusPendencia.RESOLVIDA), anyLong(),
                any())).thenReturn(pendenciaFalsa());

        mvc.perform(post("/api/pendencias/1/resolver").with(comPapel(papel)))
                .andExpect(status().isOk());
    }

    /** A linha "Apagar: ADMIN" da matriz — o caso que o usuário pediu explicitamente. */
    @ParameterizedTest
    @ValueSource(strings = { "ANALISTA", "GESTOR" })
    void analistaEGestorNaoPodemApagar(String papel) throws Exception {
        mvc.perform(delete("/api/pendencias/1").with(comPapel(papel)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));

        verifyNoInteractions(pendenciaService);
    }

    @Test
    void administradorPodeApagar() throws Exception {
        mvc.perform(delete("/api/pendencias/1").with(comPapel("ADMINISTRADOR")))
                .andExpect(status().isNoContent());
    }

    @Test
    void semTokenRecebe401ComOMesmoFormatoDeErro() throws Exception {
        // 401 vem do filtro, não do @RestControllerAdvice — o formato só é igual porque o
        // SecurityConfig delega ao handlerExceptionResolver.
        mvc.perform(get("/api/pendencias"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NAO_AUTENTICADO"));

        verifyNoInteractions(pendenciaService);
    }

    @Test
    void tokenSemPapelReconhecidoRecebe403() throws Exception {
        mvc.perform(get("/api/pendencias").with(jwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(pendenciaService);
    }

    @Test
    void cancelarExigeMotivoNoCorpo() throws Exception {
        mvc.perform(post("/api/pendencias/1/cancelar")
                .with(comPapel("ANALISTA"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACAO"))
                .andExpect(jsonPath("$.erros[0].campo").value("motivo"));
    }
}
