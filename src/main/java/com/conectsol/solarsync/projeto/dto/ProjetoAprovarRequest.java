package com.conectsol.solarsync.projeto.dto;

import java.time.LocalDate;

/** Data opcional: ausente, assume hoje. */
public record ProjetoAprovarRequest(LocalDate dataAprovacao) {
}
