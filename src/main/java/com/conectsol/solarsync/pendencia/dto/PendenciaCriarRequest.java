package com.conectsol.solarsync.pendencia.dto;

import java.time.Instant;

import com.conectsol.solarsync.pendencia.TipoPendencia;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Não aceita {@code status}: pendência nasce sempre ABERTA. Mudar status é feito pelos
 * endpoints de ação, que passam pelo service e disparam automação e auditoria.
 *
 * @param solicitadoEm opcional; ausente, assume agora. Aceito para a importação da planilha,
 *                     onde a data real é do passado.
 */
public record PendenciaCriarRequest(
        @NotNull Long clienteId,
        @NotNull TipoPendencia tipo,
        Instant solicitadoEm,
        Long responsavelId,
        @Size(max = 1000) String observacao) {
}
