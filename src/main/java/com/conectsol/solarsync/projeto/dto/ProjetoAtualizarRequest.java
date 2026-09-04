package com.conectsol.solarsync.projeto.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.conectsol.solarsync.projeto.TipoProjeto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** <b>Sem campo status</b>: transições só pelos endpoints de ação. */
public record ProjetoAtualizarRequest(
        @NotNull TipoProjeto tipoProjeto,
        Long analistaResponsavelId,
        LocalDate dataRecebimento,
        LocalDate dataArt,
        @PositiveOrZero BigDecimal potenciaKwp) {
}
