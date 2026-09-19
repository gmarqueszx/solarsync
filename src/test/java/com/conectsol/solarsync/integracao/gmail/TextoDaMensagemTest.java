package com.conectsol.solarsync.integracao.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A extração do corpo é a parte chata da API do Gmail: base64url, árvore MIME e o corpo quase
 * nunca na raiz. Testada sobre árvores montadas à mão, sem servidor — é para isso que a lógica
 * está separada do cliente HTTP.
 */
class TextoDaMensagemTest {

    @Test
    void leOCorpoNaRaizQuandoAMensagemEhSimples() {
        GmailApi.Parte raiz = parte("text/plain", "Solicitação 4410023 deferida.");

        assertThat(TextoDaMensagem.de(raiz)).contains("Solicitação 4410023 deferida.");
    }

    @Test
    void prefereTextoPuroQuandoHaAsDuasVersoes() {
        GmailApi.Parte raiz = multipart(
                parte("text/plain", "versão em texto"),
                parte("text/html", "<p>versão em <b>HTML</b></p>"));

        assertThat(TextoDaMensagem.de(raiz))
                .contains("versão em texto")
                .doesNotContain("HTML");
    }

    /** Remetente que só manda HTML existe, e ignorá-lo deixaria o parser sem nada para ler. */
    @Test
    void caiParaOHtmlSemMarcacaoQuandoNaoHaTextoPuro() {
        GmailApi.Parte raiz = multipart(parte("text/html", """
                <html><head><style>p { color: red }</style></head>
                <body><p>Solicita&ccedil;&atilde;o 4410023</p><p>Projeto <b>deferido</b>.</p>
                </body></html>"""));

        String texto = TextoDaMensagem.de(raiz);

        assertThat(texto).contains("4410023").contains("deferido");
        assertThat(texto).doesNotContain("<p>").doesNotContain("color: red");
        // </p> vira quebra de linha: sem isso o motivo da reprova colaria numa linha só e o
        // recorte por linha do parser pegaria o e-mail inteiro.
        assertThat(texto.lines().count()).isGreaterThan(1);
    }

    @Test
    void ignoraAnexoParaNaoDecodificarPdfComoTexto() {
        GmailApi.Parte anexo = new GmailApi.Parte("application/pdf", "projeto.pdf", List.of(),
                corpo("conteúdo binário que não é para ser lido"), null);
        GmailApi.Parte raiz = multipart(parte("text/plain", "corpo de verdade"), anexo);

        assertThat(TextoDaMensagem.de(raiz))
                .contains("corpo de verdade")
                .doesNotContain("binário");
    }

    @Test
    void percorreArvoreAninhada() {
        GmailApi.Parte alternativa = multipart(parte("text/plain", "texto aninhado"));
        GmailApi.Parte raiz = multipart(alternativa);

        assertThat(TextoDaMensagem.de(raiz)).contains("texto aninhado");
    }

    /** Corpo ilegível vira vazio, não exceção: um e-mail estranho não pode derrubar a leitura. */
    @Test
    void corpoIlegivelOuAusenteViraTextoVazio() {
        GmailApi.Parte semCorpo = new GmailApi.Parte("text/plain", null, List.of(), null, null);
        GmailApi.Parte base64Invalido = new GmailApi.Parte("text/plain", null, List.of(),
                new GmailApi.Corpo("!!!não é base64!!!", 0), null);

        assertThat(TextoDaMensagem.de(semCorpo)).isEmpty();
        assertThat(TextoDaMensagem.de(base64Invalido)).isEmpty();
        assertThat(TextoDaMensagem.de(null)).isEmpty();
    }

    /** O Gmail devolve base64url sem preenchimento; o decodificador do Java aceita assim. */
    @Test
    void decodificaBase64UrlSemPreenchimento() {
        String semPreenchimento = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("ção".getBytes(StandardCharsets.UTF_8));
        GmailApi.Parte raiz = new GmailApi.Parte("text/plain", null, List.of(),
                new GmailApi.Corpo(semPreenchimento, 0), null);

        assertThat(TextoDaMensagem.de(raiz)).isEqualTo("ção");
    }

    private static GmailApi.Parte parte(String tipo, String texto) {
        return new GmailApi.Parte(tipo, null, List.of(), corpo(texto), null);
    }

    private static GmailApi.Parte multipart(GmailApi.Parte... filhas) {
        return new GmailApi.Parte("multipart/alternative", null, List.of(), null,
                List.of(filhas));
    }

    private static GmailApi.Corpo corpo(String texto) {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        return new GmailApi.Corpo(Base64.getUrlEncoder().encodeToString(bytes), bytes.length);
    }
}
