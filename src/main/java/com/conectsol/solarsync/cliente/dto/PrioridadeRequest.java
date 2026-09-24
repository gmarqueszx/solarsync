package com.conectsol.solarsync.cliente.dto;

import java.time.LocalDate;

import com.conectsol.solarsync.cliente.MotivoPrioridade;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Pedido de prioridade para um cliente.
 *
 * @param motivo         obrigatório. Prioridade sem motivo não dá para revisar depois: quem olha
 *                       a fila daqui a duas semanas não tem como saber se ainda vale
 * @param observacao     o caso concreto, principalmente quando o motivo é {@code OUTRO}
 * @param dataInstalacao <b>obrigatória apenas</b> quando o motivo é {@code INSTALACAO_ADIANTADA}
 *                       — a validação cruzada fica no {@code ClienteService}, porque o Bean
 *                       Validation não olha dois campos de uma vez sem uma anotação de classe, e
 *                       a regra precisa valer também para origens que não passam pelo controller.
 *                       <p>
 *                       É a mesma data de {@code projeto.data_instalacao}, não uma segunda: o
 *                       service a copia para o projeto do cliente assim que ele existe, e é de lá
 *                       que a etapa de vistoria a lê
 */
public record PrioridadeRequest(
        @NotNull MotivoPrioridade motivo,
        @Size(max = 500) String observacao,
        LocalDate dataInstalacao) {
}
