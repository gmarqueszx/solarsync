package com.conectsol.solarsync.unificacao.dto;

import jakarta.validation.constraints.Size;

/**
 * Filtros combináveis; todos opcionais (nulo = não filtra). As filas de trabalho da etapa 4:
 * {@code feita=false} é o que falta unificar; {@code feita=true} com
 * {@code desligamentoStatus=NAO_SOLICITADO} é quem já unificou e ainda precisa pedir o
 * desligamento; e {@code desligamentoStatus=SOLICITADO,OS_ABERTA} é o que está aguardando
 * retorno — a fila que mais se perde de vista.
 */
public record UnificacaoFiltro(
        Long clienteId,
        Long projetistaId,
        @Size(max = 100) String cidade,
        Boolean feita,
        java.util.Set<com.conectsol.solarsync.unificacao.StatusDesligamento> desligamentoStatus,
        @Size(max = 150) String q) {
}
