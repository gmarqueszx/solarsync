package com.conectsol.solarsync.projeto.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.conectsol.solarsync.auth.dto.UsuarioResumoResponse;
import com.conectsol.solarsync.cliente.dto.ClienteResumoResponse;
import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.projeto.StatusProjeto;
import com.conectsol.solarsync.projeto.TipoProjeto;

public record ProjetoResponse(
        Long id,
        ClienteResumoResponse cliente,
        TipoProjeto tipoProjeto,
        UsuarioResumoResponse analistaResponsavel,
        LocalDate dataRecebimento,
        LocalDate dataArt,
        LocalDate dataEncaminhado,
        StatusProjeto status,
        String motivoReprova,
        LocalDate dataAprovacao,
        LocalDate dataInstalacao,
        Instant criadoEm,
        Instant atualizadoEm) {

    public static ProjetoResponse de(Projeto projeto) {
        return new ProjetoResponse(
                projeto.getId(),
                ClienteResumoResponse.de(projeto.getCliente()),
                projeto.getTipoProjeto(),
                UsuarioResumoResponse.de(projeto.getAnalistaResponsavel()),
                projeto.getDataRecebimento(),
                projeto.getDataArt(),
                projeto.getDataEncaminhado(),
                projeto.getStatus(),
                projeto.getMotivoReprova(),
                projeto.getDataAprovacao(),
                projeto.getDataInstalacao(),
                projeto.getCriadoEm(),
                projeto.getAtualizadoEm());
    }
}
