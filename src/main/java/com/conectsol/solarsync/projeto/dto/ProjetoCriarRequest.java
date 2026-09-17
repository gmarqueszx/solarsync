package com.conectsol.solarsync.projeto.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.conectsol.solarsync.projeto.TipoProjeto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Criação manual, para o caso em que o projeto não veio da resolução de uma pendência. Nasce
 * sempre em RECEBIDO — não aceita {@code status}.
 *
 * @param numeroSolicitacao normalmente vazio aqui: o número só existe depois que a Coelba
 *                          recebe o projeto. Aceito na criação para a importação da planilha,
 *                          onde o projeto já chega enviado
 */
public record ProjetoCriarRequest(
        @NotNull Long clienteId,
        @NotNull TipoProjeto tipoProjeto,
        Long analistaResponsavelId,
        LocalDate dataRecebimento,
        LocalDate dataArt,
        @Size(max = 50) String numeroSolicitacao,
        @PositiveOrZero BigDecimal potenciaKwp) {
}
