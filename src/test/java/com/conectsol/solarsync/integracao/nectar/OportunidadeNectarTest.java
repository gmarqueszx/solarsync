package com.conectsol.solarsync.integracao.nectar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Mapeamento do formato do Nectar. Os casos abaixo são <b>dados reais</b> da carteira da
 * ConectSol, lidos da API em 17/09/2026 — inclusive os defeitos, que são o que importa testar.
 */
class OportunidadeNectarTest {

    private static final String CAMPO_PAGAMENTO = "Data do Pagamento";

    @Test
    void oNomeDoClienteVemDoCliente() {
        assertThat(real().nomeDoCliente()).isEqualTo("HUDSON OLIVEIRA SOUZA");
    }

    /**
     * O campo de nome do cliente no Nectar costuma trazer o título inteiro da oportunidade — em
     * 3 de 6 importações reais. Os três exemplos abaixo são os que apareceram no banco.
     */
    @Test
    void extraiONomeQuandoOCampoTrazOTituloInteiroDaOportunidade() {
        assertThat(comNomeDeCliente(
                "CLEIDE COQUEIRO MOREIRA_BRUMADO_JUDSON_R$4.000,00+20xR$425,00_380/220")
                .nomeDoCliente()).isEqualTo("CLEIDE COQUEIRO MOREIRA");
        assertThat(comNomeDeCliente(
                "ADEILTON DA SILVA SOUZA_BRUMADO_DEILSON_R$4.000,00+R$4.200,00_380/220v.")
                .nomeDoCliente()).isEqualTo("ADEILTON DA SILVA SOUZA");
        assertThat(comNomeDeCliente("(AMPLIAÇÃO 04) GELADÃO COMERCIO DE BEBIDAS LTDA "
                + "( ANNA C ALVES LEITE DISTRIBUIDORA DE BEBIDAS) _BRUMADO_JUDSON_R% 3.750,00")
                .nomeDoCliente())
                .isEqualTo("GELADÃO COMERCIO DE BEBIDAS LTDA ( ANNA C ALVES LEITE "
                        + "DISTRIBUIDORA DE BEBIDAS)");
    }

    /**
     * A guarda: exige três ou mais trechos. Nome de pessoa não tem isso, então um sublinhado
     * solto num nome legítimo não faz o nome ser cortado.
     */
    @Test
    void naoCortaNomeQueNaoTemACaraDeUmTitulo() {
        assertThat(comNomeDeCliente("HUDSON OLIVEIRA SOUZA").nomeDoCliente())
                .isEqualTo("HUDSON OLIVEIRA SOUZA");
        assertThat(comNomeDeCliente("Frigorifico Nordeste Frios e Congelados").nomeDoCliente())
                .isEqualTo("Frigorifico Nordeste Frios e Congelados");
        // Dois trechos não bastam: cortar aqui daria "Proposta".
        assertThat(comNomeDeCliente("Proposta_Comercial").nomeDoCliente())
                .isEqualTo("Proposta_Comercial");
    }

    /** O prefixo entre parênteses nunca é nome, então sai mesmo sem a convenção completa. */
    @Test
    void tiraOPrefixoEntreParentesesMesmoSemAConvencao() {
        assertThat(comNomeDeCliente("(Relocação) Dener Cesário Silva Machado").nomeDoCliente())
                .isEqualTo("Dener Cesário Silva Machado");
    }

    @Test
    void caiParaOContatoEDepoisParaONomeDaOportunidade() {
        OportunidadeNectar semCliente = new OportunidadeNectar(1L, "Venda_Brumado_Deilson",
                "VALIDADO PELO FINANCEIRO", funil(), null,
                new OportunidadeNectar.Pessoa(9L, "Ana Contato", null, null, null),
                null, null, null);
        OportunidadeNectar soComNome = new OportunidadeNectar(2L, "Venda_Brumado_Deilson",
                "VALIDADO PELO FINANCEIRO", funil(), null, null, null, null, null);

        assertThat(semCliente.nomeDoCliente()).isEqualTo("Ana Contato");
        // Caindo para o nome da oportunidade, o que vale é o primeiro trecho da convenção — o
        // resto é cidade, vendedor e valor.
        assertThat(soComNome.nomeDoCliente()).isEqualTo("Venda");
    }

    /**
     * A cidade só existe dentro do nome da oportunidade, por convenção da equipe
     * ({@code CLIENTE_CIDADE_VENDEDOR_VALOR_TENSÃO}) — não há campo de cidade na API, nem na
     * oportunidade, nem no cliente, nem nos campos personalizados.
     */
    @Test
    void extraiACidadeDoNomeDaOportunidade() {
        assertThat(real().cidadeDoCliente()).isEqualTo("VITÓRIA DA CONQUISTA");
    }

    /** O prefixo entre parênteses marca o tipo do caso e não faz parte da convenção. */
    @Test
    void descartaOPrefixoEntreParentesesAntesDeContarAsPosicoes() {
        assertThat(comNome("(Ampliação) Frigorifico Nordeste Frios e Congelados LTDA_Camaçari_"
                + "Rafael_R$ ").cidadeDoCliente()).isEqualTo("Camaçari");
        assertThat(comNome("(PROJETO 2)JOSE NERI SANTIAGO FILHO DE CACULE_CACULE_JUDSON_"
                + "35.000(BANCO)_220/380").cidadeDoCliente()).isEqualTo("CACULE");
        assertThat(comNome("(AMPLIAÇÃO 02) AABB -ASSOCIACAO ATLETICA BANCO DO BRASIL_ BRUMADO_ "
                + "DEILSON_ R$ 4.400,00_ ").cidadeDoCliente()).isEqualTo("BRUMADO");
    }

    /**
     * Quando a convenção não foi seguida, é melhor cidade nula que um pedaço qualquer do nome:
     * uma cidade errada sobrevive na listagem, uma nula pede para ser preenchida.
     */
    @Test
    void naoInventaCidadeQuandoONomeNaoSegueAConvencao() {
        // A cidade ficou de fora e o valor ocupou a segunda posição.
        assertThat(comNome("ANTONIO CAIRES CHAVES_R$ 1000 +R$ 1000 + 15X R$ 453,34_380/220V")
                .cidadeDoCliente()).isNull();
        // Segunda posição com a tensão. Passou na primeira versão da guarda, que barrava por
        // lista de formatos — daí a regra ser "tem palavra de três letras ou mais".
        assertThat(comNome("CLIENTE_380/220V_x").cidadeDoCliente()).isNull();
        assertThat(comNome("CLIENTE_220/380_x").cidadeDoCliente()).isNull();
        assertThat(comNome("CLIENTE_35.000(BANCO)").cidadeDoCliente()).isEqualTo("35.000(BANCO)");
        // Sem separador não há segunda posição.
        assertThat(comNome("JOÃO WANDERLEY").cidadeDoCliente()).isNull();
        assertThat(comNome(null).cidadeDoCliente()).isNull();
    }

    /**
     * O limite conhecido da convenção: quando o nome usa {@code _} para outra coisa, a segunda
     * posição é uma palavra qualquer e não há como saber que não é cidade. Documentado aqui e nos
     * buracos conhecidos da seção 11 — a analista corrige na triagem, e cidade errada não decide
     * nada no fluxo.
     */
    @Test
    void naoTemComoDistinguirPalavraQualquerDeCidade() {
        assertThat(comNome("Proposta_Comercial_CONECTSOL").cidadeDoCliente())
                .isEqualTo("Comercial");
    }

    @Test
    void telefoneVemDoTelefonePrincipalDoCliente() {
        assertThat(real().telefoneDoCliente()).isEqualTo("+5577999295821");
    }

    @Test
    void vendedorEhOResponsavelPeloNegocio() {
        assertThat(real().nomeDoVendedor()).isEqualTo("Rodrigo soares");
    }

    /** O campo personalizado "Data do Pagamento" vem digitado, em dd/MM/yyyy. */
    @Test
    void dataDePagamentoVemDoCampoPersonalizado() {
        assertThat(real().dataDePagamento(CAMPO_PAGAMENTO))
                .isEqualTo(LocalDate.of(2026, 9, 2));
    }

    /**
     * Pouco mais da metade dos negócios tem o campo preenchido. Sem ele, o momento em que a
     * oportunidade entrou na etapa "VALIDADO PELO FINANCEIRO" é exatamente quando o financeiro
     * validou — o gatilho real da etapa 1 do fluxo.
     */
    @Test
    void semOCampoPreenchidoCaiParaAEntradaNaEtapa() {
        OportunidadeNectar semCampo = new OportunidadeNectar(29396646L,
                "(PROJETO 2)JOSE NERI_CACULE_JUDSON_35.000", "VALIDADO PELO FINANCEIRO", funil(),
                null, null, null, "2026-05-21T17:36:15.313Z", Map.of());

        assertThat(semCampo.dataDePagamento(CAMPO_PAGAMENTO))
                .isEqualTo(LocalDate.of(2026, 5, 21));
    }

    @Test
    void campoPersonalizadoVazioNaoContaComoPreenchido() {
        OportunidadeNectar vazio = new OportunidadeNectar(1L, "C_Brumado_V",
                "VALIDADO PELO FINANCEIRO", funil(), null, null, null,
                "2026-06-22T12:06:36.264Z", Map.of(CAMPO_PAGAMENTO, ""));

        assertThat(vazio.dataDePagamento(CAMPO_PAGAMENTO))
                .isEqualTo(LocalDate.of(2026, 6, 22));
    }

    /**
     * ⚠️ {@code dataCriacao} vem corrompida em boa parte da base — anos 0024, 0026, 0028 — e por
     * isso não é lida em lugar nenhum. Usá-la faria a métrica de tempo parado render dois mil
     * anos. Este teste existe para o dia em que alguém pensar em acrescentá-la como recurso: os
     * valores abaixo são reais.
     */
    @Test
    void dataCriacaoCorrompidaNaoEntraNaConta() {
        OportunidadeNectar nenhumaDataUtil = new OportunidadeNectar(1L, "C_Brumado_V",
                "VALIDADO PELO FINANCEIRO", funil(), null, null, null, null, Map.of());

        assertThat(nenhumaDataUtil.dataDePagamento(CAMPO_PAGAMENTO)).isNull();
        // Se um dia vier a ser lida, é isto que o parser produziria.
        assertThat(OportunidadeNectar.paraData("0028-05-01T03:00:00.000Z"))
                .isEqualTo(LocalDate.of(28, 5, 1));
    }

    @Test
    void aceitaDataIsoOuBrasileiraEIgnoraOIlegivel() {
        assertThat(OportunidadeNectar.paraData("2026-09-15")).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(OportunidadeNectar.paraData("15/09/2026")).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(OportunidadeNectar.paraData("2026-09-15T14:30:00Z"))
                .isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(OportunidadeNectar.paraData("quinze de setembro")).isNull();
        assertThat(OportunidadeNectar.paraData(null)).isNull();
    }

    /** Oportunidade real, id 29839213, funil "5- Financeiro", em VALIDADO PELO FINANCEIRO. */
    private static OportunidadeNectar real() {
        return new OportunidadeNectar(
                29839213L,
                "HUDSON OLIVEIRA SOUZA_VITÓRIA DA CONQUISTA_RODRIGO_3X + R$ 666,67 + 48X + "
                        + "R$ 526,40_380/220V",
                "VALIDADO PELO FINANCEIRO",
                funil(),
                new OportunidadeNectar.Pessoa(57066424L, "HUDSON OLIVEIRA SOUZA ",
                        "+5577999295821", "+5577999295821", "hudson@gmail.com"),
                new OportunidadeNectar.Pessoa(57066424L, "HUDSON OLIVEIRA SOUZA ", null, null,
                        null),
                new OportunidadeNectar.Responsavel(175661L, "Rodrigo soares"),
                "2026-09-03T11:39:31.825Z",
                Map.of(CAMPO_PAGAMENTO, "02/09/2026"));
    }

    private static OportunidadeNectar comNome(String nome) {
        return new OportunidadeNectar(1L, nome, "VALIDADO PELO FINANCEIRO", funil(), null, null,
                null, null, null);
    }

    private static OportunidadeNectar comNomeDeCliente(String nomeDoCliente) {
        return new OportunidadeNectar(1L, "Titulo_Brumado_Judson", "VALIDADO PELO FINANCEIRO",
                funil(), new OportunidadeNectar.Pessoa(9L, nomeDoCliente, null, null, null),
                null, null, null, null);
    }

    private static OportunidadeNectar.FunilVenda funil() {
        return new OportunidadeNectar.FunilVenda(58569L, "5- Financeiro");
    }
}
