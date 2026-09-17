package com.conectsol.solarsync.projeto.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.conectsol.solarsync.projeto.TipoProjeto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** <b>Sem campo status</b>: transições só pelos endpoints de ação. */
public record ProjetoAtualizarRequest(
        @NotNull TipoProjeto tipoProjeto,
        Long analistaResponsavelId,
        LocalDate dataRecebimento,
        LocalDate dataArt,
        @Size(max = 50) String numeroSolicitacao,
        @PositiveOrZero BigDecimal potenciaKwp) {
}
