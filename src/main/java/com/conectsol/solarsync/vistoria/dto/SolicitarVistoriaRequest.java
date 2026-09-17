package com.conectsol.solarsync.vistoria.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * @param dataSolicitacao opcional; ausente, assume hoje. Aceita data passada para a importação
 *                        da planilha e para registro atrasado
 */
public record SolicitarVistoriaRequest(
        @NotNull Long projetoId,
        LocalDate dataSolicitacao) {
}
