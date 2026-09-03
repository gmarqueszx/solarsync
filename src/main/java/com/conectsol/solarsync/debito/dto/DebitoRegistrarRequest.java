package com.conectsol.solarsync.debito.dto;

import java.time.Instant;

import com.conectsol.solarsync.debito.StatusDebito;

import jakarta.validation.constraints.NotNull;

/**
 * Registra o resultado da consulta de débito na agência virtual da Coelba. Há no máximo um
 * débito por cliente (constraint no banco): consultar de novo atualiza o mesmo registro, o que
 * mantém o histórico de idas e vindas em {@code historico_status} em vez de espalhado em linhas.
 *
 * @param consultadoEm quando a consulta foi feita; ausente, assume agora
 */
public record DebitoRegistrarRequest(
        @NotNull StatusDebito status,
        Instant consultadoEm) {
}
