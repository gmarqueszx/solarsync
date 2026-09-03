package com.conectsol.solarsync.unificacao.dto;

import jakarta.validation.constraints.Size;

/**
 * Filtros combináveis; todos opcionais (nulo = não filtra). {@code feita=false} é a fila de
 * trabalho da unificação, e {@code feita=true&desligamento=false} mostra quem já unificou mas
 * ainda tem medidor para desligar.
 */
public record UnificacaoFiltro(
        Long clienteId,
        Long projetistaId,
        @Size(max = 100) String cidade,
        Boolean feita,
        Boolean desligamento,
        @Size(max = 150) String q) {
}
