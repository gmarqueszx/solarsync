package com.conectsol.solarsync.pendencia.dto;

import com.conectsol.solarsync.pendencia.StatusAcompanhamento;

/**
 * Troca só o acompanhamento, a partir da linha da tabela. {@code null} limpa. Endpoint próprio
 * porque a listagem não traz a observação, e editar pelo {@code PUT} obrigaria a tela a buscar a
 * pendência inteira só para não apagar o resto.
 */
public record AcompanhamentoRequest(StatusAcompanhamento statusAcompanhamento) {
}
