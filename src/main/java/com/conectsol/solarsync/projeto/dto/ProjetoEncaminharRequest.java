package com.conectsol.solarsync.projeto.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Size;

/**
 * Tudo opcional: sem {@code dataEncaminhado}, assume hoje. Aceitar as datas explicitamente é o
 * que permite registrar envio retroativo na importação da planilha.
 *
 * @param numeroSolicitacao número devolvido pela Coelba ao receber o projeto. É pedido aqui
 *                          porque é aqui que ele passa a existir; opcional porque às vezes o
 *                          retorno demora e o analista registra o envio antes
 */
public record ProjetoEncaminharRequest(
        LocalDate dataArt,
        LocalDate dataEncaminhado,
        @Size(max = 50) String numeroSolicitacao) {
}
