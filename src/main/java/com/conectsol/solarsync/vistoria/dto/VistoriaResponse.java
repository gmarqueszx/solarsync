package com.conectsol.solarsync.vistoria.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.conectsol.solarsync.cliente.dto.ClienteResumoResponse;
import com.conectsol.solarsync.vistoria.StatusVistoria;
import com.conectsol.solarsync.vistoria.Vistoria;

/**
 * Traz o cliente além do projeto porque as telas de vistoria são navegadas por cliente — o
 * analista pensa "a vistoria da dona Maria", não "a vistoria do projeto 482".
 */
public record VistoriaResponse(
        Long id,
        Long projetoId,
        ClienteResumoResponse cliente,
        LocalDate dataSolicitacao,
        StatusVistoria status,
        LocalDate dataResultado,
        LocalDate dataInstalacaoDoProjeto,
        Instant criadoEm,
        Instant atualizadoEm) {

    public static VistoriaResponse de(Vistoria vistoria) {
        return new VistoriaResponse(
                vistoria.getId(),
                vistoria.getProjeto().getId(),
                ClienteResumoResponse.de(vistoria.getProjeto().getCliente()),
                vistoria.getDataSolicitacao(),
                vistoria.getStatus(),
                vistoria.getDataResultado(),
                vistoria.getProjeto().getDataInstalacao(),
                vistoria.getCriadoEm(),
                vistoria.getAtualizadoEm());
    }
}
