package com.conectsol.solarsync.integracao.gmail;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Leitura da caixa de e-mail pela API REST do Gmail, com o access token renovado a partir do
 * refresh token configurado.
 * <p>
 * Sem as bibliotecas cliente do Google, pelo mesmo motivo que levou o login Google a ser escrito
 * sobre uma dependência mínima antes de sair de cena: são duas chamadas GET e uma de token, e o
 * {@code google-api-services-gmail} traria toda a pilha HTTP e de JSON do Google para conviver
 * com o Jackson 3 do Boot 4.
 * <p>
 * Só lê. Nenhuma chamada aqui escreve na caixa — nem rótulo, nem marcar como lida —, então o
 * escopo pedido no consentimento é apenas {@code gmail.readonly}. É o que torna a tabela
 * {@code email_coelba} necessária: sem poder marcar a mensagem no Gmail, a marca de "já
 * processei" tem de morar aqui.
 */
@Component
@ConditionalOnProperty(prefix = "solarsync.gmail", name = "ativo", havingValue = "true")
class GmailClient {

    private static final Logger log = LoggerFactory.getLogger(GmailClient.class);

    private static final String URL_TOKEN = "https://oauth2.googleapis.com/token";
    private static final String URL_GMAIL = "https://gmail.googleapis.com/gmail/v1";

    /** Renova um pouco antes de expirar, para a chamada seguinte não cair num token vencido. */
    private static final Duration FOLGA_DE_RENOVACAO = Duration.ofSeconds(60);

    private final RestClient token;
    private final RestClient gmail;
    private final GmailProperties propriedades;

    private String accessToken;
    private Instant expiraEm = Instant.EPOCH;

    GmailClient(RestClient.Builder construtor, GmailProperties propriedades) {
        this.propriedades = propriedades;
        this.token = construtor.clone().baseUrl(URL_TOKEN).build();
        this.gmail = construtor.clone().baseUrl(URL_GMAIL).build();

        if (!propriedades.credenciaisCompletas()) {
            log.error("solarsync.gmail.ativo=true sem credenciais completas: informe "
                    + "client-id, client-secret e refresh-token. A leitura do e-mail da Coelba "
                    + "vai falhar em toda execução até isso ser resolvido.");
        }
    }

    /**
     * Ids das mensagens que casam com a consulta configurada, da mais recente para a mais antiga
     * (ordem do Gmail). Só ids: o conteúdo custa uma chamada por mensagem, e a maioria já foi
     * processada numa execução anterior — quem decide o que vale buscar é
     * {@code RetornoCoelbaJob}, consultando {@code email_coelba} antes.
     */
    List<String> listarIds() {
        return listar(propriedades.consulta());
    }

    /**
     * Os ids dos e-mails que citam algum dos números de solicitação informados — ou seja, os
     * projetos que ainda esperam retorno da Coelba.
     * <p>
     * É o recorte que o usuário pediu em 19/09/2026 para a produção: o portal manda ~30 e-mails
     * por dia, e só os desses projetos podem virar mudança de status. Sem ele, cada execução
     * busca e registra dezenas de mensagens que não têm o que aplicar, enchendo
     * {@code email_coelba} de ruído.
     * <p>
     * <b>Em lotes</b>, porque a lista de projetos aguardando pode ter centenas de números e a
     * consulta do Gmail tem limite de tamanho. Cada lote é uma listagem — barata — e não uma
     * busca de mensagem, que é o que custa. Os ids saem sem repetição: um mesmo e-mail não pode
     * casar com dois lotes, mas a deduplicação é garantia barata contra um número repetido na
     * lista.
     */
    List<String> listarIdsDosProjetos(List<String> numerosDeSolicitacao) {
        if (numerosDeSolicitacao.isEmpty()) {
            // Nenhum projeto esperando retorno: não há e-mail que possa mudar coisa alguma.
            // Consultar assim mesmo traria a caixa inteira, que é o oposto do que se quer.
            return List.of();
        }

        Set<String> ids = new LinkedHashSet<>();
        int tamanho = propriedades.numerosPorConsulta();
        for (int inicio = 0; inicio < numerosDeSolicitacao.size(); inicio += tamanho) {
            List<String> lote = numerosDeSolicitacao.subList(
                    inicio, Math.min(inicio + tamanho, numerosDeSolicitacao.size()));
            ids.addAll(listar(propriedades.consulta() + " {" + String.join(" ", lote) + "}"));
        }
        return List.copyOf(ids);
    }

    private List<String> listar(String consulta) {
        GmailApi.ListaMensagens resposta = gmail.get()
                .uri(construtor -> construtor
                        .path("/users/{usuario}/messages")
                        // ⚠️ A consulta entra como VALOR de variável, nunca direto no
                        // queryParam: o recorte por projetos usa a sintaxe de grupo do Gmail,
                        // {2609290073 2609300515}, e o construtor de URI lia as chaves como
                        // variável de template — "Not enough variable values to expand" em toda
                        // execução, sem uma chamada sequer ao Gmail. Como valor, ela é codificada
                        // inteira, chaves incluídas.
                        .queryParam("q", "{q}")
                        .queryParam("maxResults", propriedades.maximoPorExecucao())
                        .build(Map.of("usuario", propriedades.usuario(), "q", consulta)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenValido())
                .retrieve()
                .body(GmailApi.ListaMensagens.class);

        if (resposta == null || resposta.messages() == null) {
            return List.of();
        }
        return resposta.messages().stream().map(GmailApi.Referencia::id).toList();
    }

    /** A mensagem inteira, já reduzida ao que o parser da Coelba precisa. */
    MensagemGmail buscar(String id) {
        GmailApi.Mensagem mensagem = gmail.get()
                .uri(construtor -> construtor
                        .path("/users/{usuario}/messages/{id}")
                        .queryParam("format", "full")
                        .build(propriedades.usuario(), id))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenValido())
                .retrieve()
                .body(GmailApi.Mensagem.class);

        if (mensagem == null) {
            return new MensagemGmail(id, null, "", null);
        }

        String assunto = cabecalho(mensagem, "Subject");
        String corpo = TextoDaMensagem.de(mensagem.payload());

        // Assunto concatenado no começo do texto: em muitos retornos da Coelba o número da
        // solicitação está no assunto, e o parser recebe um texto só para procurar.
        String texto = assunto == null ? corpo : assunto + "\n" + corpo;

        return new MensagemGmail(mensagem.id(), assunto, texto, recebidoEm(mensagem));
    }

    private static String cabecalho(GmailApi.Mensagem mensagem, String nome) {
        if (mensagem.payload() == null || mensagem.payload().headers() == null) {
            return null;
        }
        return mensagem.payload().headers().stream()
                .filter(cabecalho -> nome.equalsIgnoreCase(cabecalho.name()))
                .map(GmailApi.Cabecalho::value)
                .findFirst()
                .orElse(null);
    }

    private static Instant recebidoEm(GmailApi.Mensagem mensagem) {
        if (mensagem.internalDate() == null || mensagem.internalDate().isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(mensagem.internalDate().trim()));
        } catch (NumberFormatException inesperado) {
            return null;
        }
    }

    /**
     * Troca o refresh token por um access token quando o atual está perto de vencer. O refresh
     * token do Google não expira por tempo — o que o invalida é revogar o acesso ou trocar a
     * senha da conta —, então não há nada a renovar de forma persistente: o access token vive em
     * memória e é reconstruído depois de cada restart.
     * <p>
     * {@code synchronized} porque o agendamento é sequencial hoje, mas duas leituras
     * concorrentes gerariam duas renovações e uma sobrescreveria a outra no meio do uso.
     */
    private synchronized String accessTokenValido() {
        if (accessToken != null && Instant.now().isBefore(expiraEm.minus(FOLGA_DE_RENOVACAO))) {
            return accessToken;
        }

        MultiValueMap<String, String> corpo = new LinkedMultiValueMap<>();
        corpo.add("client_id", propriedades.clientId());
        corpo.add("client_secret", propriedades.clientSecret());
        corpo.add("refresh_token", propriedades.refreshToken());
        corpo.add("grant_type", "refresh_token");

        GmailApi.RespostaToken resposta = token.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(corpo)
                .retrieve()
                .body(GmailApi.RespostaToken.class);

        if (resposta == null || resposta.access_token() == null) {
            throw new IllegalStateException(
                    "Google não devolveu access token para o refresh token configurado");
        }

        accessToken = resposta.access_token();
        long duracao = resposta.expires_in() == null ? 3600L : resposta.expires_in();
        expiraEm = Instant.now().plusSeconds(duracao);
        return accessToken;
    }
}
