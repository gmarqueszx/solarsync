package com.conectsol.solarsync.projeto.dto;

import com.conectsol.solarsync.projeto.StatusProjeto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Válvula de escape restrita a ADMINISTRADOR: pula a máquina de estados, mas <b>mantém a
 * auditoria</b>. Existe porque na importação da planilha os dados chegam em qualquer ordem, e
 * porque erro operacional acontece — sem isso, um registro em estado errado ficaria travado.
 * A justificativa é obrigatória justamente para o histórico explicar o salto.
 */
public record CorrigirStatusRequest(
        @NotNull StatusProjeto novoStatus,
        @NotBlank @Size(max = 1000) String justificativa) {
}
