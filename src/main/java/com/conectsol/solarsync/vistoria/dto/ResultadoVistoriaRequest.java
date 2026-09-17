package com.conectsol.solarsync.vistoria.dto;

import java.time.LocalDate;

/** Data do resultado; ausente, assume hoje. */
public record ResultadoVistoriaRequest(LocalDate dataResultado) {
}
