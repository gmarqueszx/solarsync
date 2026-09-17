package com.conectsol.solarsync.common.config;

import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.conectsol.solarsync.common.security.UsuarioAutenticado;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    static {
        // Sem isto, o springdoc documentaria o principal injetado por @Autenticado como se
        // fosse um conjunto de query params do endpoint.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(UsuarioAutenticado.class);
    }

    @Bean
    OpenAPI solarsyncOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SolarSync API")
                        .version("v1")
                        .description("""
                                API do controle de homologação de projetos solares da ConectSol.

                                Autenticação: POST /api/auth/login (e-mail e senha). Use o \
                                accessToken como Bearer. Não há auto-cadastro: as contas são \
                                criadas por ADMINISTRADOR ou GESTOR em POST /api/usuarios. Não \
                                há logout no servidor — a API é stateless e o cliente descarta \
                                os tokens.

                                Mudanças de status são feitas por endpoints de ação \
                                (ex.: POST /api/pendencias/{id}/resolver), nunca por PUT: é o \
                                que garante a automação entre etapas e a auditoria."""))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
