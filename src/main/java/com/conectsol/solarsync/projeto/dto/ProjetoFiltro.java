package com.conectsol.solarsync.projeto.dto;

import java.time.LocalDate;
import java.util.Set;

import com.conectsol.solarsync.projeto.StatusProjeto;
import com.conectsol.solarsync.projeto.TipoProjeto;

import jakarta.validation.constraints.Size;

/** Filtros combináveis; todos opcionais (nulo = não filtra). */
public record ProjetoFiltro(
        Long clienteId,
        Set<StatusProjeto> status,
        TipoProjeto tipoProjeto,
        Long analistaResponsavelId,
        @Size(max = 150) String q,
        LocalDate recebidoDe,
        LocalDate recebidoAte) {
}
