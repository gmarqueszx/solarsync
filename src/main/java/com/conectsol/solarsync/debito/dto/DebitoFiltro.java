package com.conectsol.solarsync.debito.dto;

import java.util.Set;

import com.conectsol.solarsync.debito.StatusDebito;
import com.conectsol.solarsync.debito.TipoDebito;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param tipo                que etapa o débito trava; é o que separa as duas abas da tela
 * @param consultadoAntesDe   permite achar débitos com consulta velha — o dado é um retrato da
 *                            agência virtual num momento, não uma verdade permanente
 * @param paradoHaMaisDeDias  só os que estão ATIVO há mais de N dias. É a fila do financeiro:
 *                            cliente travado tempo demais é o que motiva a cobrança
 */
public record DebitoFiltro(
        Long clienteId,
        TipoDebito tipo,
        Set<StatusDebito> status,
        @Size(max = 150) String q,
        java.time.Instant consultadoAntesDe,
        @Positive Integer paradoHaMaisDeDias) {
}
