package com.conectsol.solarsync.projeto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Cobre todos os 36 pares de transição implicitamente: para cada status, afirma exatamente o
 * conjunto de destinos permitidos.
 */
class StatusProjetoTest {

    private static Set<StatusProjeto> destinosPermitidos(StatusProjeto origem) {
        return Arrays.stream(StatusProjeto.values())
                .filter(origem::podeIrPara)
                .collect(Collectors.toSet());
    }

    @Test
    void recebidoVaiParaAguardandoEnvioOuDiretoParaEncaminhado() {
        // Pular AGUARDANDO_ENVIO é legítimo: aquele estado só existe quando algo bloqueia o envio.
        assertThat(destinosPermitidos(StatusProjeto.RECEBIDO))
                .containsExactlyInAnyOrder(StatusProjeto.AGUARDANDO_ENVIO,
                        StatusProjeto.ENCAMINHADO);
    }

    @Test
    void aguardandoEnvioVaiParaEncaminhadoOuVoltaParaRecebido() {
        assertThat(destinosPermitidos(StatusProjeto.AGUARDANDO_ENVIO))
                .containsExactlyInAnyOrder(StatusProjeto.ENCAMINHADO, StatusProjeto.RECEBIDO);
    }

    @Test
    void encaminhadoSoVaiParaAprovadoOuReprovado() {
        assertThat(destinosPermitidos(StatusProjeto.ENCAMINHADO))
                .containsExactlyInAnyOrder(StatusProjeto.APROVADO, StatusProjeto.REPROVADO);
    }

    @Test
    void reprovadoVaiParaReencaminhadoOuVoltaAAguardarEnvio() {
        assertThat(destinosPermitidos(StatusProjeto.REPROVADO))
                .containsExactlyInAnyOrder(StatusProjeto.REENCAMINHADO,
                        StatusProjeto.AGUARDANDO_ENVIO);
    }

    @Test
    void reencaminhadoSoVaiParaAprovadoOuReprovado() {
        assertThat(destinosPermitidos(StatusProjeto.REENCAMINHADO))
                .containsExactlyInAnyOrder(StatusProjeto.APROVADO, StatusProjeto.REPROVADO);
    }

    @Test
    void aprovadoAindaPodeSerReprovadoPorqueACoelbaRevisa() {
        assertThat(destinosPermitidos(StatusProjeto.APROVADO))
                .containsExactly(StatusProjeto.REPROVADO);
    }

    @Test
    void naoSePodeAprovarProjetoQueNuncaFoiEnviado() {
        // O caso que corromperia a métrica de tempo até aprovação do dashboard.
        assertThat(StatusProjeto.RECEBIDO.podeIrPara(StatusProjeto.APROVADO)).isFalse();
        assertThat(StatusProjeto.AGUARDANDO_ENVIO.podeIrPara(StatusProjeto.APROVADO)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(StatusProjeto.class)
    void nenhumStatusDeclaraTransicaoParaSiMesmo(StatusProjeto status) {
        // Repetir o status é tratado como no-op idempotente no service, não como transição.
        assertThat(status.podeIrPara(status)).isFalse();
    }
}
