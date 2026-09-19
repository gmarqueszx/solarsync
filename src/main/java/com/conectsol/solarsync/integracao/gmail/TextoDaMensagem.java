package com.conectsol.solarsync.integracao.gmail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Extrai o texto legível de uma mensagem do Gmail. Separado do cliente HTTP porque é a parte
 * chata e a única com regra de verdade — e assim dá para testá-la com uma árvore MIME montada à
 * mão, sem servidor nenhum.
 */
final class TextoDaMensagem {

    private TextoDaMensagem() {
    }

    /**
     * Prefere {@code text/plain}; cai para {@code text/html} com as marcações removidas quando o
     * remetente só manda HTML — o que acontece, e é o caso em que ignorar o HTML deixaria o
     * parser sem nada para ler.
     * <p>
     * Anexo é ignorado: parte com {@code filename} é arquivo, não corpo, e um PDF decodificado
     * como texto só produziria lixo para o parser casar por acidente.
     */
    static String de(GmailApi.Parte raiz) {
        if (raiz == null) {
            return "";
        }

        String textoPuro = procurar(raiz, "text/plain");
        if (!textoPuro.isBlank()) {
            return textoPuro;
        }

        return semMarcacaoHtml(procurar(raiz, "text/html"));
    }

    /** Percorre a árvore MIME em profundidade e junta todo corpo do tipo pedido. */
    private static String procurar(GmailApi.Parte parte, String tipoDesejado) {
        if (parte.filename() != null && !parte.filename().isBlank()) {
            return "";
        }

        StringBuilder acumulado = new StringBuilder();

        String tipo = parte.mimeType() == null ? "" : parte.mimeType().toLowerCase();
        if (tipo.startsWith(tipoDesejado) && parte.body() != null) {
            acumulado.append(decodificar(parte.body().data()));
        }

        List<GmailApi.Parte> filhas = parte.parts();
        if (filhas != null) {
            for (GmailApi.Parte filha : filhas) {
                String daFilha = procurar(filha, tipoDesejado);
                if (!daFilha.isBlank()) {
                    acumulado.append('\n').append(daFilha);
                }
            }
        }

        return acumulado.toString();
    }

    /**
     * O Gmail devolve o corpo em base64url, normalmente sem o preenchimento de {@code =}. O
     * decodificador do Java aceita a entrada sem preenchimento; o que ele não aceita é caractere
     * fora do alfabeto, e é isso que o {@code catch} cobre — corpo ilegível vira string vazia,
     * que o parser trata como não reconhecido, em vez de derrubar a leitura da caixa inteira.
     */
    private static String decodificar(String base64url) {
        if (base64url == null || base64url.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getUrlDecoder().decode(base64url), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ilegivel) {
            return "";
        }
    }

    /**
     * Limpeza suficiente para o parser: fora {@code <script>}/{@code <style>} inteiros, as
     * marcações viram espaço, {@code <br>} e {@code </p>} viram quebra de linha (para o motivo da
     * reprova não colar numa linha só) e as entidades mais comuns voltam a ser texto.
     */
    private static String semMarcacaoHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return html
                .replaceAll("(?is)<(script|style)\\b.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</tr>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
