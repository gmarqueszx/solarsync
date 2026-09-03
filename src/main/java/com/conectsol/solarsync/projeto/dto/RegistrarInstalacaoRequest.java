package com.conectsol.solarsync.projeto.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

/**
 * A informação chega pelo grupo de projetos instalados e é registrada à mão, normalmente dias
 * depois — por isso a data é obrigatória e não assume "hoje": chutar hoje falsearia a métrica
 * de tempo até a solicitação da vistoria.
 */
public record RegistrarInstalacaoRequest(
        @NotNull @PastOrPresent LocalDate dataInstalacao) {
}
