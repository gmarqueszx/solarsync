package com.conectsol.solarsync.unificacao.dto;

import java.time.LocalDate;

/**
 * Data da solicitação ou da conclusão do desligamento. Opcional: ausente, assume hoje. Aceita
 * data passada porque o registro costuma vir depois do fato — e é dela que sai o tempo de espera.
 */
public record DataDesligamentoRequest(LocalDate data) {
}
