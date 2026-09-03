package com.conectsol.solarsync.debito.dto;

import java.time.Instant;

import com.conectsol.solarsync.cliente.dto.ClienteResumoResponse;
import com.conectsol.solarsync.debito.Debito;
import com.conectsol.solarsync.debito.StatusDebito;

public record DebitoResponse(
        Long id,
        ClienteResumoResponse cliente,
        StatusDebito status,
        Instant ultimaConsultaEm,
        Instant criadoEm,
        Instant atualizadoEm) {

    public static DebitoResponse de(Debito debito) {
        return new DebitoResponse(
                debito.getId(),
                ClienteResumoResponse.de(debito.getCliente()),
                debito.getStatus(),
                debito.getUltimaConsultaEm(),
                debito.getCriadoEm(),
                debito.getAtualizadoEm());
    }
}
