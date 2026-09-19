package com.conectsol.solarsync.integracao.nectar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.conectsol.solarsync.integracao.nectar.NectarProperties.EtapaDeEntrada;

class NectarPropertiesTest {

    private static final NectarProperties PADRAO =
            new NectarProperties(false, null, null, null, null, null, 0, 0);

    @Test
    void aplicaOsPadroesQuandoNadaFoiConfigurado() {
        assertThat(PADRAO.baseUrl()).isEqualTo("https://app.nectarcrm.com.br/crm/api/1");
        assertThat(PADRAO.intervalo()).isEqualTo(Duration.ofMinutes(10));
        assertThat(PADRAO.campoDataPagamento()).isEqualTo("Data do Pagamento");
        // O Nectar limita displayLength a 200; valor fora da faixa cai no limite.
        assertThat(PADRAO.paginaTamanho()).isEqualTo(200);
        assertThat(new NectarProperties(false, null, null, null, null, null, 500, 0)
                .paginaTamanho()).isEqualTo(200);
    }

    /** As duas etapas que o usuário confirmou em 17/09/2026 como a entrada do fluxo. */
    @Test
    void asEtapasPadraoSaoAsDuasDaConectsol() {
        assertThat(PADRAO.etapasDeEntrada()).containsExactly(
                new EtapaDeEntrada("5- Financeiro", "VALIDADO PELO FINANCEIRO"),
                new EtapaDeEntrada("4- Nota Fiscal",
                        "ADIANTAR PROJETO COELBA PARA BANCO OU VENDEDOR"));
    }

    @Test
    void reconheceAsOportunidadesDasEtapasDeEntrada() {
        assertThat(PADRAO.ehEtapaDeEntrada(
                oportunidade("5- Financeiro", "VALIDADO PELO FINANCEIRO"))).isTrue();
        assertThat(PADRAO.ehEtapaDeEntrada(oportunidade("4- Nota Fiscal",
                "ADIANTAR PROJETO COELBA PARA BANCO OU VENDEDOR"))).isTrue();
    }

    /**
     * O caso que obriga a etapa a ser identificada por funil <b>e</b> nome. "VALIDADO PELO
     * FINANCEIRO" também aparece no funil "7- Instalação" como "FUNCIONANDO | VALIDADO PELO
     * FINANCEIRO" — e lá o cliente está instalado e funcionando, não entrando no fluxo. Casar só
     * pelo nome da etapa (ou só pela sequência, que é relativa ao funil) traria cliente errado.
     */
    @Test
    void naoConfundeEtapaDeNomeParecidoEmOutroFunil() {
        assertThat(PADRAO.ehEtapaDeEntrada(oportunidade(
                "7- Instalação", "FUNCIONANDO | VALIDADO PELO FINANCEIRO"))).isFalse();
        // Mesmo nome de etapa, funil errado.
        assertThat(PADRAO.ehEtapaDeEntrada(
                oportunidade("7- Instalação", "VALIDADO PELO FINANCEIRO"))).isFalse();
        // Funil certo, etapa errada: "Pendente" é a outra etapa do 5- Financeiro, e é onde estão
        // 299 das 308 oportunidades do funil.
        assertThat(PADRAO.ehEtapaDeEntrada(oportunidade("5- Financeiro", "Pendente"))).isFalse();
    }

    /**
     * Os dois nomes são digitados por gente no painel do Nectar. O custo de errar é um cliente
     * que nunca aparece na fila da triagem, sem erro nenhum em lugar algum.
     */
    @Test
    void comparaSemAcentoSemCaixaESemEspacoSobrando() {
        assertThat(PADRAO.ehEtapaDeEntrada(
                oportunidade("  5- financeiro ", "validado pelo financeiro"))).isTrue();
        assertThat(PADRAO.ehEtapaDeEntrada(oportunidade(
                "4- NOTA FISCAL", "Adiantar  Projeto  Coelba  para  Banco  ou  Vendedor")))
                .isTrue();

        NectarProperties comAcento = new NectarProperties(false, null, null,
                List.of(new EtapaDeEntrada("7- Instalação", "APROVAÇÃO")), null, null, 0, 0);
        assertThat(comAcento.ehEtapaDeEntrada(oportunidade("7- instalacao", "aprovacao")))
                .isTrue();
    }

    @Test
    void funilOuEtapaNulosNaoCasamNadaENaoExplodem() {
        assertThat(PADRAO.ehEtapaDeEntrada(oportunidade(null, "VALIDADO PELO FINANCEIRO")))
                .isFalse();
        assertThat(PADRAO.ehEtapaDeEntrada(oportunidade("5- Financeiro", null))).isFalse();
    }

    /** Duas etapas do mesmo funil têm de dar uma consulta só — a consulta é por funil. */
    @Test
    void funisAConsultarNaoRepeteOMesmoFunil() {
        NectarProperties duasDoMesmoFunil = new NectarProperties(false, null, null, List.of(
                new EtapaDeEntrada("5- Financeiro", "VALIDADO PELO FINANCEIRO"),
                new EtapaDeEntrada("5- Financeiro", "Pendente")), null, null, 0, 0);

        assertThat(duasDoMesmoFunil.funisAConsultar()).containsExactly("5- Financeiro");
        assertThat(duasDoMesmoFunil.etapasDoFunil("5- Financeiro"))
                .containsExactly("VALIDADO PELO FINANCEIRO", "Pendente");
    }

    @Test
    void funisAConsultarSaoOsDoisPadroes() {
        assertThat(PADRAO.funisAConsultar())
                .containsExactly("5- Financeiro", "4- Nota Fiscal");
    }

    @Test
    void removeBarraFinalDaBaseUrlParaNaoGerarCaminhoComBarraDobrada() {
        NectarProperties comBarra = new NectarProperties(false,
                "https://app.nectarcrm.com.br/crm/api/1/", "t", null, null, null, 0, 0);

        assertThat(comBarra.baseUrl()).isEqualTo("https://app.nectarcrm.com.br/crm/api/1");
    }

    private static OportunidadeNectar oportunidade(String funil, String etapa) {
        return new OportunidadeNectar(1L, "Cliente_Cidade_Vendedor", etapa,
                funil == null ? null : new OportunidadeNectar.FunilVenda(1L, funil),
                null, null, null, null, null);
    }
}
