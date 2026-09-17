package com.conectsol.solarsync.vistoria.dto;

import java.time.LocalDate;
import java.util.Set;

import com.conectsol.solarsync.vistoria.StatusVistoria;

import jakarta.validation.constraints.Size;

/** Filtros combináveis; todos opcionais (nulo = não filtra). */
public record VistoriaFiltro(
        Long projetoId,
        Long clienteId,
        Set<StatusVistoria> status,
        @Size(max = 150) String q,
        LocalDate solicitadaDe,
        LocalDate solicitadaAte) {
}
