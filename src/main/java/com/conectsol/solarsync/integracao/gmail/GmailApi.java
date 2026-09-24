package com.conectsol.solarsync.integracao.gmail;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Formato da resposta da API do Gmail, transcrito só no que este projeto usa. Campo que não está
 * aqui é descartado pelo Jackson.
 * <p>
 * Os nomes dos componentes são os nomes do JSON do Google, em inglês — é o único lugar do
 * projeto onde isso acontece, e de propósito: renomear para português exigiria anotação de
 * mapeamento em cada campo e faria a comparação com a documentação do Gmail deixar de ser
 * imediata. A tradução para o domínio é o {@link MensagemGmail}.
 */
final class GmailApi {

    private GmailApi() {
    }

    /** Resposta de {@code users.messages.list}: só ids, sem conteúdo. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ListaMensagens(List<Referencia> messages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Referencia(String id) {
    }

    /**
     * Resposta de {@code users.messages.get?format=full}.
     *
     * @param internalDate quando o Gmail recebeu a mensagem, em milissegundos de época. É esta
     *                     data que vira a data de aprovação do projeto — e não a da execução do
     *                     job —, pela mesma razão que o débito registra a data da consulta e não
     *                     a da digitação (seção 5): senão a métrica mediria a agilidade do job
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Mensagem(String id, String internalDate, Parte payload) {
    }

    /**
     * Um nó da árvore MIME. O corpo de um e-mail de verdade quase nunca está na raiz: vem em
     * {@code parts}, normalmente com uma versão {@code text/plain} e uma {@code text/html}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Parte(String mimeType, String filename, List<Cabecalho> headers, Corpo body,
            List<Parte> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cabecalho(String name, String value) {
    }

    /** @param data conteúdo em base64url (o alfabeto do Gmail, com {@code -} e {@code _}) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Corpo(String data, Integer size) {
    }

    /** Resposta do endpoint de token do Google ao trocar o refresh token por um access token. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RespostaToken(String access_token, Long expires_in) {
    }
}
