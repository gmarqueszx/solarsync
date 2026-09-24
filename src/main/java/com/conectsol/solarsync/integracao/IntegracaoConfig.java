package com.conectsol.solarsync.integracao;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import com.conectsol.solarsync.integracao.gmail.CoelbaProperties;
import com.conectsol.solarsync.integracao.gmail.GmailProperties;
import com.conectsol.solarsync.integracao.nectar.NectarProperties;
import com.conectsol.solarsync.integracao.nectar.NectarSaidaProperties;

/**
 * Liga o agendador das integrações de entrada (seção 9 do CLAUDE.md) e provê o construtor de
 * cliente HTTP que elas usam.
 * <p>
 * O {@code @EnableScheduling} é inofensivo quando nenhuma integração está ligada: os jobs são
 * beans condicionais ({@code solarsync.nectar.ativo} e {@code solarsync.gmail.ativo}, ambos
 * falsos por padrão), então sem configuração não existe nada agendado para rodar. É o que mantém
 * os testes e o desenvolvimento local sem chamada externa acontecendo por trás.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        NectarProperties.class,
        NectarSaidaProperties.class,
        GmailProperties.class,
        CoelbaProperties.class })
public class IntegracaoConfig {

    /**
     * ⚠️ <b>Pegadinha do Spring Boot 4 que custou um boot quebrado</b>: o
     * {@code spring-boot-starter-webmvc} <b>não</b> traz a autoconfiguração do
     * {@link RestClient.Builder} (ela mora no módulo {@code spring-boot-restclient}, separado).
     * Injetar {@code RestClient.Builder} direto derruba a aplicação com
     * "No qualifying bean of type 'RestClient$Builder'" — e só quando uma integração está
     * ligada, porque os clientes HTTP são beans condicionais. Nenhum teste pegou: com
     * {@code ativo=false} os beans não existem.
     * <p>
     * Declarar o bean aqui resolve sem acrescentar dependência. As respostas do Nectar e do
     * Gmail têm dezenas de campos que não nos interessam, e os DTOs são anotados com
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} em vez de depender da configuração do
     * Jackson que a autoconfiguração do Boot aplicaria — assim a tolerância a campo desconhecido
     * é do próprio DTO, e não de um bem que este builder não herda.
     * <p>
     * <b>Escopo protótipo</b>, como o do Boot, e não por simetria: o builder é mutável, e
     * {@code baseUrl}/{@code defaultHeader} alteram a instância. Sendo singleton, o cliente do
     * Nectar e o do Gmail — que apontam para hosts diferentes — sobrescreveriam a configuração um
     * do outro assim que as duas integrações estivessem ligadas juntas.
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder restClientIntegracao() {
        return RestClient.builder();
    }
}
