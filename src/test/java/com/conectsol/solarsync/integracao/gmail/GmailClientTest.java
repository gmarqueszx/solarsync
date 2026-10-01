package com.conectsol.solarsync.integracao.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * O cliente HTTP do Gmail contra um servidor simulado. Nenhum outro teste o toca: o bean só
 * existe com {@code solarsync.gmail.ativo=true}, que os testes desligam — e foi assim que a
 * consulta com chaves quebrou em toda execução em homologação (01/10/2026) com a suíte verde.
 */
class GmailClientTest {

    private MockRestServiceServer servidor;
    private GmailClient cliente;

    @BeforeEach
    void preparar() {
        RestClient.Builder construtor = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(construtor).build();
        cliente = new GmailClient(construtor, new GmailProperties(true, false, true, 0,
                "id", "segredo", "refresh", null, null, 0, null));

        servidor.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"access_token": "tok", "expires_in": 3600}
                        """, MediaType.APPLICATION_JSON));
    }

    /**
     * O recorte por projetos manda {@code {n1 n2}} — a sintaxe de grupo do Gmail. As chaves
     * precisam chegar ao Gmail como texto, e não ser lidas como variável de template da URI.
     */
    @Test
    void consultaComOsNumerosEntreChavesChegaInteiraAoGmail() {
        String esperada = "from:noreplyportalgd@neoenergia.com newer_than:7d "
                + "{2609290073 2609300515}";

        servidor.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://gmail.googleapis.com/gmail/v1/users/me/messages?")))
                .andExpect(queryParam("q",
                        URLEncoder.encode(esperada, StandardCharsets.UTF_8).replace("+", "%20")))
                .andRespond(withSuccess("""
                        {"messages": [{"id": "m1"}, {"id": "m2"}]}
                        """, MediaType.APPLICATION_JSON));

        List<String> ids = cliente.listarIdsDosProjetos(List.of("2609290073", "2609300515"));

        assertThat(ids).containsExactly("m1", "m2");
        servidor.verify();
    }
}
