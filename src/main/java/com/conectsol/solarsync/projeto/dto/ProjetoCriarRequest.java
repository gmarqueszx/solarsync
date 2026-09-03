package com.conectsol.solarsync.projeto.dto;

import java.time.LocalDate;

import com.conectsol.solarsync.projeto.TipoProjeto;

import jakarta.validation.constraints.NotNull;

/**
 * Criação manual, para o caso em que o projeto não veio da resolução de uma pendência. Nasce
 * sempre em RECEBIDO — não aceita {@code status}.
 */
public record ProjetoCriarRequest(
        @NotNull Long clienteId,
        @NotNull TipoProjeto tipoProjeto,
        Long analistaResponsavelId,
        LocalDate dataRecebimento,
        LocalDate dataArt) {
}
