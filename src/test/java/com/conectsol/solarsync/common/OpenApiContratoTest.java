package com.conectsol.solarsync.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Rede de segurança do contrato: detecta em tempo de build tanto uma incompatibilidade do
 * springdoc com esta versão do Spring Boot quanto o desaparecimento acidental de um endpoint
 * que o solarsync-web consome.
 */
@AutoConfigureMockMvc
class OpenApiContratoTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void publicaContratoComOsEndpointsChaveEOEsquemaDeSeguranca() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("SolarSync API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme")
                        .value("bearer"))
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login/google']").exists())
                .andExpect(jsonPath("$.paths['/api/pendencias']").exists())
                .andExpect(jsonPath("$.paths['/api/pendencias/{id}/resolver']").exists())
                .andExpect(jsonPath("$.paths['/api/projetos/{id}/reprovar']").exists())
                .andExpect(jsonPath("$.paths['/api/projetos/{id}/corrigir-status']").exists());
    }

    /**
     * A regra inviolável da seção 4 do CLAUDE.md, codificada em teste: histórico é populado só
     * pelo listener de domínio. Um endpoint de escrita aqui destruiria a confiabilidade de
     * todas as métricas do dashboard.
     */
    @Test
    void naoExisteEndpointDeEscritaDeHistorico() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/historico-status']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/pendencias/{id}/historico'].post")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/projetos/{id}/historico'].post")
                        .doesNotExist());
    }
}
