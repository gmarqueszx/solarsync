package com.conectsol.solarsync.pendencia.dto;

import com.conectsol.solarsync.pendencia.TipoPendencia;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Edição dos dados da pendência. <b>Sem campo status</b> — de propósito: é o que impede o
 * frontend trocar status por fora do service, contornando automação e auditoria.
 * <p>
 * Também não traz o {@code statusAcompanhamento}: ele muda pela linha da tabela
 * ({@code PUT /{id}/acompanhamento}), e tê-lo aqui faria corrigir o tipo apagar a anotação de
 * quem esquecesse de reenviá-la.
 */
public record PendenciaAtualizarRequest(
        @NotNull TipoPendencia tipo,
        Long responsavelId,
        @Size(max = 1000) String observacao,
        @Size(max = 50) String numeroSolicitacao) {
}
