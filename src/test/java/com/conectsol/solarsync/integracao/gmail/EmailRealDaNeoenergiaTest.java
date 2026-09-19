package com.conectsol.solarsync.integracao.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Os e-mails <b>reais</b> do Portal da Geração Distribuída, conferidos com o usuário em
 * 19/09/2026 e colados aqui como chegam — nomes de cliente e números incluídos.
 * <p>
 * Isto fecha o item que a seção 9 do CLAUDE.md marcava como bloqueante para ligar a integração:
 * "a redação real do e-mail da Coelba ainda não foi vista". Foi vista, e desmentiu o desenho
 * anterior do parser, que procurava "deferida"/"indeferida" no corpo. <b>Nenhuma dessas palavras
 * existe.</b> O que o e-mail traz é uma linha {@code Etapa atual:} com o nome da etapa nova.
 * <p>
 * Estes três casos são a razão de o parser ter dois caminhos, e ficam num arquivo separado dos
 * testes sintéticos de propósito: quando a Neoenergia mudar o formato, é aqui que se cola o
 * e-mail novo, e é este arquivo que diz o que foi realmente observado em vez de suposto.
 */
class EmailRealDaNeoenergiaTest {

    private final ParserEmailCoelba parser = new ParserEmailCoelba(
            new CoelbaProperties(null, null, null, null, null, null, null, null, null, null, 0, 0));

    /**
     * Confirmação de envio: o projeto acabou de entrar na fila da distribuidora. Não decide
     * nada, e o projeto segue ENCAMINHADO — que é o status certo para "esperando a Coelba".
     */
    @Test
    void confirmacaoDeEnvioEhAcompanhamentoENaoMudaNada() {
        RetornoCoelba retorno = parser.ler("""
                Portal da Geração Distribuída: Solicitação 2609094548

                Prezado(a) EDIKELE SANTOS SILVA,

                Sua solicitação de acesso à rede de distribuição para a instalação de geração \
                distribuída passou para uma nova etapa. Informamos que as informações e \
                documentação foram recebidas e serão avaliadas pela distribuidora.

                Número da solicitação: 2609094548

                Etapa anterior: Aguardando Documentação

                Etapa atual: Em Análise Técnica

                Data limite para conclusão da etapa atual: 29/09/2026

                Para acompanhar a solicitação acesse: \
                https://gdneoenergiacoelba.neoenergia.com/pages/consulta.jsf?s=rZS4SInbTicHt8QcSL4idQ%3D%3D
                """);

        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.EM_ANALISE);
        assertThat(retorno.ambiguo()).isFalse();
        assertThat(retorno.numeros()).contains("2609094548");
    }

    /**
     * ⚠️ <b>O caso que derrubou o desenho anterior.</b> Este é o e-mail de aprovação, e o seu
     * parágrafo em prosa é <b>idêntico</b>, palavra por palavra, ao da confirmação de envio
     * acima. A única diferença está nas duas linhas de etapa.
     * <p>
     * Pior: a etapa <i>anterior</i> aqui é "Em Análise Técnica". Um parser que procurasse termos
     * no texto inteiro leria "em análise" e devolveria acompanhamento — a aprovação seria
     * descartada em silêncio e o projeto ficaria parado em ENCAMINHADO para sempre, com a Coelba
     * tendo aprovado. É por isso que só {@code Etapa atual} é lida.
     */
    @Test
    void aprovacaoVemDaEtapaNovaEnaoDoCorpo() {
        RetornoCoelba retorno = parser.ler("""
                Portal da Geração Distribuída: Solicitação 2608198955

                Prezado(a) ANDERSON RIBEIRO BRITTO,

                Sua solicitação de acesso à rede de distribuição para a instalação de geração \
                distribuída passou para uma nova etapa. Informamos que as informações e \
                documentação foram recebidas e serão avaliadas pela distribuidora.

                Número da solicitação: 2608198955

                Etapa anterior: Em Análise Técnica

                Etapa atual: Aguardando solicitação de vistoria e Conexão

                Data limite para conclusão da etapa atual: 24/12/2026

                Para acompanhar a solicitação acesse: \
                https://gdneoenergiacoelba.neoenergia.com/pages/consulta.jsf?s=AdtiObKwVNtrm1oX6zfu%2Bg%3D%3D
                """);

        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.APROVADO);
        assertThat(retorno.ambiguo()).isFalse();
        assertThat(retorno.numeros()).contains("2608198955");
    }

    /**
     * O cancelamento é o único dos três que <b>não</b> traz linha de etapa — por isso o parser
     * mantém o caminho de busca por termos. E o motivo vem da linha rotulada: o termo
     * "cancelada" aparece lá na saudação, então o recorte genérico em volta dele traria o
     * cabeçalho e o link junto, em vez da frase que a analista precisa ler.
     */
    @Test
    void cancelamentoEhReprovacaoComOMotivoDaLinhaRotulada() {
        RetornoCoelba retorno = parser.ler("""
                Portal da Geração Distribuída: Solicitação 2609094548

                Prezado(a) EDIKELE SANTOS SILVA,

                Sua solicitação de acesso à rede de distribuição para instalação de geração \
                distribuída foi cancelada.

                Número da solicitação: 2609094548

                Motivo do cancelamento: Tensão Informada (127V) diverge da cadastrada no \
                sistema Coelba (380/220).

                Data do cancelamento: 10/09/2026
                """);

        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.REPROVADO);
        assertThat(retorno.ambiguo()).isFalse();
        assertThat(retorno.numeros()).contains("2609094548");
        assertThat(retorno.motivo())
                .startsWith("Tensão Informada (127V) diverge")
                .doesNotContain("Prezado")
                .doesNotContain("Data do cancelamento");
    }

    /**
     * A etapa que a Neoenergia ainda não usou. Não reconhecido é a resposta certa — e
     * explicitamente <b>não</b> o retorno da busca por termos, que naquele corpo diria
     * "recebidas e serão avaliadas" e não significa nada. Quem decide o que a etapa nova
     * significa é uma pessoa, acrescentando o valor em {@code solarsync.coelba.etapas-*}.
     */
    @Test
    void etapaDesconhecidaNaoViraPalpiteAPartirDoCorpo() {
        RetornoCoelba retorno = parser.ler("""
                Portal da Geração Distribuída: Solicitação 2609094548

                Sua solicitação de acesso à rede de distribuição para a instalação de geração \
                distribuída passou para uma nova etapa. Informamos que as informações e \
                documentação foram recebidas e serão avaliadas pela distribuidora.

                Número da solicitação: 2609094548

                Etapa anterior: Em Análise Técnica

                Etapa atual: Aguardando Parecer de Acesso Revisado
                """);

        assertThat(retorno.resultado()).isNull();
        assertThat(retorno.ambiguo()).isFalse();
        // O número continua sendo extraído: o e-mail fica em email_coelba já casado com o
        // projeto, para quem for diagnosticar não precisar procurar de qual projeto se trata.
        assertThat(retorno.numeros()).contains("2609094548");
    }

    /**
     * ⚠️ <b>A variante em prosa</b>, descoberta no ensaio de 19/09/2026 e <b>um em cada cinco
     * e-mails reais</b> (33 de 160 na amostra). Não tem linha de etapa nenhuma: o avanço está
     * dentro do texto corrido. Colado do HTML real, já convertido para texto.
     * <p>
     * É o caso que justifica o parser manter o caminho de busca por termos. Sem ele, 20% do
     * volume cairia como não reconhecido para sempre — e, pior, em silêncio, porque não
     * reconhecido não muda status e não dói.
     */
    @Test
    void aceiteDaDocumentacaoEmProsaEhAcompanhamentoENaoNaoReconhecido() {
        RetornoCoelba retorno = parser.ler("""
                Portal da Geração Distribuída: Solicitação 2609166483

                Prezado(a) ALEXANDRE DE AZEVEDO SOUZA NETO,

                Sua solicitação de acesso à rede de distribuição para a instalação de geração \
                distribuída passou para uma nova etapa. Informamos que as informações e \
                documentação recebida estão de acordo com a regulação, sua solicitação passará \
                para a etapa de estudos, elaboração do projeto e orçamento.

                Número da solicitação: 2609166483
                """);

        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.EM_ANALISE);
        assertThat(retorno.ambiguo()).isFalse();
        assertThat(retorno.numeros()).contains("2609166483");
    }

    /**
     * Etapa 4 automatizada: a Coelba está executando a vistoria, logo ela foi solicitada. É a
     * etapa mais frequente de todas no volume real (53 de 160).
     */
    @Test
    void realizandoVistoriaEhVistoriaSolicitada() {
        RetornoCoelba retorno = parser.ler("""
                Portal da Geração Distribuída: Solicitação 2607179265
                Prezado(a) JOSSIVANIA PEREIRA SOUZA,
                Número da solicitação: 2607179265
                Etapa anterior: Ponto de Conexão Aprovado
                Etapa atual: Realizando vistoria e Conexão
                Data limite para conclusão da etapa atual: 11/09/2026
                """);

        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.VISTORIA_SOLICITADA);
    }

    /**
     * ⚠️ Este e-mail e o de cima são do <b>mesmo</b> projeto e chegaram com <b>quatro segundos</b>
     * de diferença, em lote atrasado — um deles com prazo já vencido. Quem desempatou a ordem
     * real foi a {@code Data limite}: 11/09 para "realizando vistoria" e 26/09 para "ponto de
     * conexão aprovado", logo o ponto de conexão vem depois.
     * <p>
     * Repare que {@code Etapa anterior} aqui diz "Solicitação Concluída", que nunca foi etapa
     * atual de nada — é lixo do portal, e a prova de que só {@code Etapa atual} pode ser lida.
     */
    @Test
    void pontoDeConexaoAprovadoEhVistoriaAprovada() {
        RetornoCoelba retorno = parser.ler("""
                Portal da Geração Distribuída: Solicitação 2607179265
                Prezado(a) JOSSIVANIA PEREIRA SOUZA,
                Número da solicitação: 2607179265
                Etapa anterior: Solicitação Concluída
                Etapa atual: Ponto de Conexão Aprovado
                Data limite para conclusão da etapa atual: 26/09/2026
                """);

        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.VISTORIA_APROVADA);
    }

    /**
     * "Solicitação Concluída" encerra o processo no portal e, por decisão do usuário, não mexe em
     * nada: quem fecha a vistoria é o ponto de conexão aprovado. Está nas etapas de
     * acompanhamento — e não fora de todas as listas — para não aparecer como não reconhecida a
     * cada execução e esconder as etapas que realmente forem novas.
     */
    @Test
    void solicitacaoConcluidaEhAcompanhamentoENaoMexeNaVistoria() {
        RetornoCoelba retorno = parser.ler("""
                Portal da Geração Distribuída: Solicitação 2607179265
                Número da solicitação: 2607179265
                Etapa atual: Solicitação Concluída
                """);

        assertThat(retorno.resultado()).isEqualTo(ResultadoCoelba.EM_ANALISE);
    }

    /**
     * O número tem de sair do texto sem que as datas e a URL entrem junto. "29/09/2026" tem
     * dígitos suficientes para ser candidato, e a URL está cheia de ruído — o que os mantém de
     * fora é a preferência pelos números rotulados ("Número da solicitação:").
     */
    @Test
    void oNumeroDaSolicitacaoNaoDisputaComAsDatasNemComALinkDeAcompanhamento() {
        RetornoCoelba retorno = parser.ler("""
                Portal da Geração Distribuída: Solicitação 2608198955
                Número da solicitação: 2608198955
                Etapa atual: Aguardando solicitação de vistoria e Conexão
                Data limite para conclusão da etapa atual: 24/12/2026
                Para acompanhar a solicitação acesse: \
                https://gdneoenergiacoelba.neoenergia.com/pages/consulta.jsf?s=AdtiObKwVNtrm1oX6zfu%2Bg%3D%3D
                """);

        assertThat(retorno.numeros()).containsExactly("2608198955");
    }
}
