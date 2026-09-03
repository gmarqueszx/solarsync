package com.conectsol.solarsync.projeto.dto;

import java.time.LocalDate;
import java.util.Set;

import com.conectsol.solarsync.projeto.StatusProjeto;
import com.conectsol.solarsync.projeto.TipoProjeto;

import jakarta.validation.constraints.Size;

/**
 * Filtros combináveis; todos opcionais (nulo = não filtra).
 *
 * @param instalado    projetos com (ou sem) data de instalação registrada
 * @param semVistoria  projetos que ainda não têm vistoria alguma. Combinado com
 *                     {@code instalado=true}, é a fila de trabalho da etapa 4: usina instalada
 *                     esperando alguém solicitar a vistoria
 */
public record ProjetoFiltro(
        Long clienteId,
        Set<StatusProjeto> status,
        TipoProjeto tipoProjeto,
        Long analistaResponsavelId,
        @Size(max = 150) String q,
        LocalDate recebidoDe,
        LocalDate recebidoAte,
        Boolean instalado,
        Boolean semVistoria) {
}
