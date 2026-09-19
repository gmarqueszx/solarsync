package com.conectsol.solarsync.common.referencia;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.conectsol.solarsync.common.AbstractIntegrationTest;

/**
 * As listas fechadas do cadastro e, principalmente, a normalização — é ela que impede a
 * importação do Nectar de furar o padrão que o cadastro pela tela respeita. Os valores de
 * entrada dos testes são os que a importação real trouxe em 17/09/2026.
 */
class ReferenciasTest extends AbstractIntegrationTest {

    @Autowired
    private Referencias referencias;

    @Test
    void carregaAsListasComOsAcentosCorretos() {
        assertThat(referencias.municipios()).hasSize(417)
                .contains("Caculé", "Tanhaçu", "Vitória da Conquista", "Camaçari",
                        "Barra do Choça");
        assertThat(referencias.vendedores()).containsExactlyInAnyOrder(
                "Rodrigo", "Deilson", "Agildo", "Alon", "Henrique", "Judson", "Andreia",
                "Anderson", "Lurane", "Zenildo");
    }

    /** Sem isto, a lista de clientes tinha quatro grafias da mesma cidade. */
    @Test
    void normalizaCidadeIgnorandoCaixaEAcento() {
        assertThat(referencias.municipioCanonico("CACULE")).isEqualTo("Caculé");
        assertThat(referencias.municipioCanonico("VITÓRIA DA CONQUISTA"))
                .isEqualTo("Vitória da Conquista");
        assertThat(referencias.municipioCanonico("Vitória Da Conquista"))
                .isEqualTo("Vitória da Conquista");
        assertThat(referencias.municipioCanonico("vitoria da conquista"))
                .isEqualTo("Vitória da Conquista");
        assertThat(referencias.municipioCanonico("  tanhaçu ")).isEqualTo("Tanhaçu");
        assertThat(referencias.municipioCanonico("BRUMADO")).isEqualTo("Brumado");
        assertThat(referencias.municipioCanonico("barra do choça")).isEqualTo("Barra do Choça");
    }

    @Test
    void cidadeQueNaoEhMunicipioDaBahiaViraNulo() {
        // "Pindobeira" é sobrenome de cliente que caiu na posição da cidade no nome da
        // oportunidade; "Sõ felix do coribe" e "sem informação" apareceram na carteira.
        assertThat(referencias.municipioCanonico("Pindobeira")).isNull();
        assertThat(referencias.municipioCanonico("sem informação")).isNull();
        assertThat(referencias.municipioCanonico("380/220V")).isNull();
        assertThat(referencias.municipioCanonico(null)).isNull();
        assertThat(referencias.municipioCanonico("   ")).isNull();
    }

    /**
     * A lista tem só o primeiro nome (é o que o cadastro pela tela oferece) e o CRM manda o nome
     * completo do responsável.
     */
    @Test
    void normalizaVendedorPeloPrimeiroNome() {
        assertThat(referencias.vendedorCanonico("Rodrigo soares")).isEqualTo("Rodrigo");
        assertThat(referencias.vendedorCanonico("Deilson Abrantes")).isEqualTo("Deilson");
        assertThat(referencias.vendedorCanonico("Judson Rocha")).isEqualTo("Judson");
        assertThat(referencias.vendedorCanonico("Andreia Guedes")).isEqualTo("Andreia");
        assertThat(referencias.vendedorCanonico("AGILDO")).isEqualTo("Agildo");
        assertThat(referencias.vendedorCanonico("ANDERSON")).isEqualTo("Anderson");
        assertThat(referencias.vendedorCanonico("Lurane")).isEqualTo("Lurane");
        // Acrescentado em 17/09/2026: a importação o encontrou como responsável de dois
        // negócios e o WARN de "não está na lista" foi o que o revelou.
        assertThat(referencias.vendedorCanonico("Zenildo")).isEqualTo("Zenildo");
    }

    /**
     * O campo "responsável" do Nectar carrega gente do administrativo além dos vendedores. Nulo
     * é o comportamento desejado: a analista escolhe na tela, em vez de o campo receber texto
     * livre pela porta dos fundos.
     */
    @Test
    void responsavelQueNaoEhVendedorViraNulo() {
        assertThat(referencias.vendedorCanonico("Thainara Gomes")).isNull();
        assertThat(referencias.vendedorCanonico("Evelin Barros")).isNull();
        assertThat(referencias.vendedorCanonico("Evelyn Natyelle")).isNull();
        assertThat(referencias.vendedorCanonico("Neres Lima Meira Junior")).isNull();
        assertThat(referencias.vendedorCanonico(null)).isNull();
    }
}
