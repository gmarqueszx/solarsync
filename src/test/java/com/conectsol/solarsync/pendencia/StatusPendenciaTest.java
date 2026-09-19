package com.conectsol.solarsync.pendencia;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class StatusPendenciaTest {

    private static Set<StatusPendencia> destinosPermitidos(StatusPendencia origem) {
        return Arrays.stream(StatusPendencia.values())
                .filter(origem::podeIrPara)
                .collect(Collectors.toSet());
    }

    @Test
    void abertaPodeResolverOuCancelar() {
        assertThat(destinosPermitidos(StatusPendencia.ABERTA))
                .containsExactlyInAnyOrder(StatusPendencia.RESOLVIDA, StatusPendencia.CANCELADA);
    }

    @Test
    void resolvidaPodeSerReaberta() {
        // Seguro: criarOuAtivarProjetoParaCliente não duplica projeto ao re-resolver.
        assertThat(destinosPermitidos(StatusPendencia.RESOLVIDA))
                .containsExactly(StatusPendencia.ABERTA);
    }

    /**
     * Existe um único estado ativo: apontar a pendência na triagem já é iniciá-la. Se alguém
     * reintroduzir um "EM_ANDAMENTO", este teste é o que acusa — e a pergunta a fazer é qual
     * decisão ele tomaria que ABERTA não toma.
     */
    @Test
    void temApenasUmStatusAtivo() {
        assertThat(StatusPendencia.values())
                .containsExactly(StatusPendencia.ABERTA, StatusPendencia.RESOLVIDA,
                        StatusPendencia.CANCELADA);
    }

    @Test
    void canceladaSoVoltaParaAberta() {
        assertThat(destinosPermitidos(StatusPendencia.CANCELADA))
                .containsExactly(StatusPendencia.ABERTA);
    }

    @Test
    void naoSePodeCancelarUmaPendenciaJaResolvida() {
        assertThat(StatusPendencia.RESOLVIDA.podeIrPara(StatusPendencia.CANCELADA)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(StatusPendencia.class)
    void nenhumStatusDeclaraTransicaoParaSiMesmo(StatusPendencia status) {
        assertThat(status.podeIrPara(status)).isFalse();
    }
}
