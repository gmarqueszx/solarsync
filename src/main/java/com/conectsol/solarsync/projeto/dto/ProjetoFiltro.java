package com.conectsol.solarsync.projeto.dto;

import java.time.LocalDate;
import java.util.Set;

import com.conectsol.solarsync.projeto.StatusProjeto;
import com.conectsol.solarsync.projeto.TipoProjeto;

import jakarta.validation.constraints.Size;

/**
 * Filtros combináveis; todos opcionais (nulo = não filtra).
 *
 * @param q                 casa nome do cliente, UC ou número de solicitação da Coelba
 * @param instalado         projetos com (ou sem) data de instalação registrada
 * @param semVistoria       projetos que ainda não têm vistoria alguma. Combinado com
 *                          {@code status=APROVADO}, é a fila de trabalho da etapa 4: projeto
 *                          homologado esperando instalação e solicitação de vistoria
 * @param travadoPorDebito  projetos por enviar cujo cliente tem débito de homologação ativo
 * @param semConsultaDebito projetos por enviar cujo cliente nunca teve o débito de homologação
 *                          consultado — é a fila de trabalho do projetista ao receber o cliente
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
        Boolean semVistoria,
        Boolean travadoPorDebito,
        Boolean semConsultaDebito) {
}
