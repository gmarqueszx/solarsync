package com.conectsol.solarsync.debito.dto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.conectsol.solarsync.auth.dto.UsuarioResumoResponse;
import com.conectsol.solarsync.cliente.dto.ClienteResumoResponse;
import com.conectsol.solarsync.debito.Debito;
import com.conectsol.solarsync.debito.StatusDebito;
import com.conectsol.solarsync.debito.TipoDebito;

/**
 * @param diasParado há quantos dias o cliente está travado por este débito. <b>Nulo</b> quando
 *                   não há débito ativo — nulo é "não está parado", e devolver 0 faria a tela
 *                   mostrar "parado há 0 dias" para quem está em dia. Mesma disciplina de
 *                   "nulo ≠ zero" das médias do dashboard
 */
public record DebitoResponse(
        Long id,
        ClienteResumoResponse cliente,
        TipoDebito tipo,
        StatusDebito status,
        Instant ultimaConsultaEm,
        Instant detectadoEm,
        Instant quitadoEm,
        Long diasParado,
        UsuarioResumoResponse consultadoPor,
        Instant criadoEm,
        Instant atualizadoEm) {

    public static DebitoResponse de(Debito debito) {
        return new DebitoResponse(
                debito.getId(),
                ClienteResumoResponse.de(debito.getCliente()),
                debito.getTipo(),
                debito.getStatus(),
                debito.getUltimaConsultaEm(),
                debito.getDetectadoEm(),
                debito.getQuitadoEm(),
                diasParado(debito),
                UsuarioResumoResponse.de(debito.getConsultadoPor()),
                debito.getCriadoEm(),
                debito.getAtualizadoEm());
    }

    private static Long diasParado(Debito debito) {
        if (debito.getStatus() != StatusDebito.ATIVO || debito.getDetectadoEm() == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(debito.getDetectadoEm(), Instant.now());
    }
}
