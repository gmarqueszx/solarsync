package com.conectsol.solarsync.integracao.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * O parser é o ponto mais arriscado das integrações: o que ele conclui muda o status de um
 * projeto sozinho e vai para o {@code historico_status}, que sustenta todas as métricas do
 * dashboard. Estes testes cobrem principalmente o que ele <b>não</b> deve concluir.
 */
class ParserEmailCoelbaTest {

    private final ParserEmailCoelba parser = new ParserEmailCoelba(
            new CoelbaProperties(null, null, null, null, null, null, null, null, null, null, 0, 0));

    @Test
    void leAprovacaoComONumeroDaSolicitacao() {
        RetornoCoelba retorno = parser.ler("""
                Neoenergia Coelba - Solicitação de acesso nº 4410023
                Informamos que a solicitação de acesso foi DEFERIDA.
                """);

        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.APROVADO);
        assertThat(retorno.numeros()).containsExactly("4410023");
        assertThat(retorno.ambiguo()).isFalse();
    }

    @Test
    void leReprovacaoEGuardaOMotivoComOsAcentosIntactos() {
        RetornoCoelba retorno = parser.ler("""
                Solicitação 4410099
                A solicitação foi reprovada por ausência da ART assinada pelo responsável.
                Reapresente a documentação.
                """);

        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.REPROVADO);
        assertThat(retorno.numeros()).containsExactly("4410099");
        // O recorte começa no início da linha, não no termo: a frase começa antes de "reprovada"
        // e cortar no termo perderia o começo dela.
        assertThat(retorno.motivo())
                .startsWith("A solicitação foi reprovada por ausência da ART")
                .contains("responsável");
    }

    /**
     * O erro mais caro que este parser pode cometer. "Não deferida" contém "deferid", que é termo
     * de aprovação — sem tratar a negação, um indeferimento seria aplicado como aprovação e o
     * projeto iria para APROVADO com a Coelba tendo recusado.
     */
    @Test
    void negacaoDeTermoDeAprovacaoEhReprovacaoENaoAprovacao() {
        RetornoCoelba retorno = parser.ler(
                "Protocolo 4410077: a solicitação não foi deferida pela distribuidora.");

        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.REPROVADO);
        assertThat(retorno.motivo()).contains("não foi deferida");
    }

    /** Afirmar duas coisas não autoriza escolher uma: o projeto fica como está. */
    @Test
    void emailComAprovacaoEReprovacaoEhAmbiguoENaoEscolhe() {
        RetornoCoelba retorno = parser.ler("""
                Solicitação 4410001 deferida.
                Solicitação 4410002 reprovada por falta de documento.
                """);

        assertThat(retorno.ambiguo()).isTrue();
        assertThat(retorno.resultado()).isNull();
    }

    @Test
    void acompanhamentoSemDecisaoEhEmAnaliseENaoNaoReconhecido() {
        RetornoCoelba retorno = parser.ler(
                "Solicitação 4410050 encontra-se em análise pela equipe técnica.");

        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.EM_ANALISE);
    }

    @Test
    void textoSemNenhumTermoConhecidoNaoConclui() {
        RetornoCoelba retorno = parser.ler(
                "Prezado cliente, segue em anexo o comprovante da solicitação 4410060.");

        assertThat(retorno.resultado()).isNull();
        assertThat(retorno.ambiguo()).isFalse();
        // Os números continuam sendo extraídos: eles vão para o registro em email_coelba, que é
        // por onde alguém descobre qual projeto o e-mail não reconhecido mencionava.
        assertThat(retorno.numeros()).containsExactly("4410060");
    }

    @Test
    void textoVazioOuNuloNaoConcluiENaoExplode() {
        assertThat(parser.ler(null).resultado()).isNull();
        assertThat(parser.ler("   ").resultado()).isNull();
        assertThat(parser.ler(null).numeros()).isEmpty();
    }

    @Test
    void numeroComPontuacaoViraApenasOsDigitos() {
        RetornoCoelba retorno = parser.ler("Solicitação nº 4.410.023-7 deferida.");

        assertThat(retorno.numeros()).containsExactly("44100237");
    }

    /**
     * Quando não há palavra anunciando protocolo, qualquer número serve de candidato. É seguro
     * porque quem confirma o número não é o formato: é o casamento com um projeto existente, em
     * {@code RetornoCoelbaService}. Errar o formato faria a integração perder e-mail; ser
     * generoso aqui só produz candidato que não casa com nada.
     */
    @Test
    void semPalavraDeProtocoloQualquerNumeroEhCandidato() {
        RetornoCoelba retorno = parser.ler("Referente ao 4410031, projeto aprovado.");

        assertThat(retorno.numeros()).contains("4410031");
        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.APROVADO);
    }

    /** Havendo número rotulado, os outros números do texto não entram — evita ruído. */
    @Test
    void numeroRotuladoTemPrecedenciaSobreOsOutrosNumerosDoTexto() {
        RetornoCoelba retorno = parser.ler("""
                Solicitação nº 4410023 deferida em 17/09/2026.
                Potência 12.50 kWp. Telefone 7199998888.
                """);

        assertThat(retorno.numeros()).containsExactly("4410023");
    }

    @Test
    void acentoECaixaNaoImpedemOReconhecimento() {
        assertThat(parser.ler("Solicitação 4410023: PROJETO APROVADO").resultado())
                .isEqualTo(ResultadoCoelba.APROVADO);
    }

    /**
     * "Indeferido" contém "deferid", que é termo de aprovação. Sem fronteira de palavra no começo
     * do termo, os dois grupos apareceriam e todo indeferimento cairia como ambíguo — ou seja, a
     * integração nunca aplicaria a reprovação anunciada com a redação mais provável da Coelba.
     */
    @Test
    void indeferidoNaoCasaComOTermoDeAprovacaoDeferid() {
        RetornoCoelba retorno = parser.ler("Solicitação 4410023: pedido indeferido.");

        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.REPROVADO);
        assertThat(retorno.ambiguo()).isFalse();
    }

    @Test
    void termosConfiguradosSubstituemOsPadroes() {
        ParserEmailCoelba comOutrosTermos = new ParserEmailCoelba(new CoelbaProperties(
                null, null, null, null, null, null, null,
                List.of("liberado para conexao"), List.of("recusado"), List.of(), 0, 0));

        assertThat(comOutrosTermos.ler("Solicitação 4410023 liberado para conexão").resultado())
                .isEqualTo(ResultadoCoelba.APROVADO);
        // "deferida" era padrão e deixou de ser: a lista configurada substitui, não acrescenta.
        assertThat(comOutrosTermos.ler("Solicitação 4410023 deferida").resultado()).isNull();
    }

    @Test
    void numeroCurtoNaoEhCandidato() {
        // Cinco dígitos é o mínimo padrão; "123" não pode virar candidato e casar por acidente.
        RetornoCoelba retorno = parser.ler("Item 123 aprovado.");

        assertThat(retorno.numeros()).isEmpty();
    }
}
