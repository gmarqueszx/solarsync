package com.conectsol.solarsync.pendencia.dto;

import java.time.Instant;
import java.util.Set;

import com.conectsol.solarsync.pendencia.StatusPendencia;
import com.conectsol.solarsync.pendencia.TipoPendencia;

import jakarta.validation.constraints.Size;

/** Filtros combináveis; todos opcionais (nulo = não filtra). */
public record PendenciaFiltro(
        Long clienteId,
        Set<StatusPendencia> status,
        TipoPendencia tipo,
        Long responsavelId,
        @Size(max = 150) String q,
        Instant solicitadoDe,
        Instant solicitadoAte) {
}
