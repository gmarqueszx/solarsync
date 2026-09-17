package com.conectsol.solarsync.historico.dto;

import java.time.Instant;

import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.historico.HistoricoStatus;

/**
 * Somente leitura. Não existe — e não deve passar a existir — endpoint de escrita de
 * histórico: ele é populado exclusivamente pelo {@code HistoricoStatusEventListener}. Um POST
 * que permitisse inserir histórico à mão destruiria a confiabilidade das métricas do
 * dashboard (CLAUDE.md seção 5).
 * <p>
 * Devolve {@code usuarioId} sem o nome de propósito: o frontend já carrega
 * {@code /api/usuarios/lookup} para os seletores e resolve o nome de lá, o que evita um join
 * por linha de histórico.
 */
public record HistoricoStatusResponse(
        Long id,
        EntidadeTipo entidadeTipo,
        Long entidadeId,
        String statusAnterior,
        String statusNovo,
        Instant ocorridoEm,
        Long usuarioId) {

    public static HistoricoStatusResponse de(HistoricoStatus historico) {
        return new HistoricoStatusResponse(
                historico.getId(),
                historico.getEntidadeTipo(),
                historico.getEntidadeId(),
                historico.getStatusAnterior(),
                historico.getStatusNovo(),
                historico.getOcorridoEm(),
                historico.getUsuarioId());
    }
}
