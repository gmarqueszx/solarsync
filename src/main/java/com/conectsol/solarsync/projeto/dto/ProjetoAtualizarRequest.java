package com.conectsol.solarsync.projeto.dto;

import java.time.LocalDate;

import com.conectsol.solarsync.projeto.TipoProjeto;

import jakarta.validation.constraints.NotNull;

/** <b>Sem campo status</b>: transições só pelos endpoints de ação. */
public record ProjetoAtualizarRequest(
        @NotNull TipoProjeto tipoProjeto,
        Long analistaResponsavelId,
        LocalDate dataRecebimento,
        LocalDate dataArt) {
}
