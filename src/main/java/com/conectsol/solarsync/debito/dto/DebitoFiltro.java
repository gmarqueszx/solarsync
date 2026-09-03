package com.conectsol.solarsync.debito.dto;

import java.util.Set;

import com.conectsol.solarsync.debito.StatusDebito;

import jakarta.validation.constraints.Size;

/**
 * @param consultadoAntesDe permite achar débitos com consulta velha — o dado é um retrato da
 *                          agência virtual num momento, não uma verdade permanente
 */
public record DebitoFiltro(
        Long clienteId,
        Set<StatusDebito> status,
        @Size(max = 150) String q,
        java.time.Instant consultadoAntesDe) {
}
