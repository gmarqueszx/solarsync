package com.conectsol.solarsync.pendencia.dto;

import java.time.Instant;
import java.util.Set;

import com.conectsol.solarsync.pendencia.StatusPendencia;
import com.conectsol.solarsync.pendencia.TipoPendencia;

import jakarta.validation.constraints.Size;

/**
 * Filtros combináveis; todos opcionais (nulo = não filtra).
 *
 * @param travadaPorDebito pendência ainda não resolvida de cliente com débito ativo — não pode
 *                         ser resolvida até a quitação. É <b>derivado</b>, não um status: a
 *                         pendência travada continua ABERTA ou EM_ANDAMENTO, porque um status
 *                         próprio precisaria ser desfeito por outro evento na hora em que o
 *                         cliente quitasse e viveria dessincronizado de {@code debito.status}
 * @param semConsultaDebito pendência de cliente sem nenhuma consulta de débito registrada —
 *                          também não pode ser resolvida, mas por falta de informação, e a
 *                          ação a tomar é consultar a agência virtual, não cobrar
 */
public record PendenciaFiltro(
        Long clienteId,
        Set<StatusPendencia> status,
        TipoPendencia tipo,
        Long responsavelId,
        @Size(max = 150) String q,
        Instant solicitadoDe,
        Instant solicitadoAte,
        Boolean travadaPorDebito,
        Boolean semConsultaDebito) {
}
