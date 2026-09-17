package com.conectsol.solarsync.unificacao.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Não aceita {@code feita} nem {@code desligamento}: esses marcos são registrados pelas ações
 * dedicadas, pelo mesmo motivo que status não entra em PUT nos outros módulos — cada marco tem
 * significado operacional e merece um ponto de entrada explícito.
 */
public record UnificacaoRequest(
        @NotNull Long clienteId,
        @Size(max = 100) String cidade,
        Long projetistaId,
        @Size(max = 1000) String informacoes) {
}
