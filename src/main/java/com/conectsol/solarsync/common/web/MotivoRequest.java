package com.conectsol.solarsync.common.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo das ações que exigem justificativa (cancelar pendência, reprovar projeto). Vive em
 * {@code common} porque é usado por mais de um módulo — histórico sem motivo não ajuda
 * ninguém a entender depois o que aconteceu.
 */
public record MotivoRequest(@NotBlank @Size(max = 1000) String motivo) {
}
