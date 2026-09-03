package com.conectsol.solarsync.cliente.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param dataPagamento marco zero das métricas do dashboard: "tempo médio sem ninguém mexer no
 *                      cliente" é medido a partir daqui
 */
public record ClienteRequest(
        @NotBlank @Size(max = 150) String nome,
        @Size(max = 100) String cidade,
        @Size(max = 150) String vendedor,
        LocalDate dataPagamento) {
}
