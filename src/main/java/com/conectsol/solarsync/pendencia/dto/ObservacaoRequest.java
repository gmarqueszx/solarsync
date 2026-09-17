package com.conectsol.solarsync.pendencia.dto;

import jakarta.validation.constraints.Size;

/** Corpo opcional das ações que só admitem uma anotação livre (iniciar, resolver, reabrir). */
public record ObservacaoRequest(@Size(max = 1000) String observacao) {
}
