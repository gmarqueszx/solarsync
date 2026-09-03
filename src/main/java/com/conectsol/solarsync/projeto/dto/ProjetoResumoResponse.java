package com.conectsol.solarsync.projeto.dto;

import java.time.LocalDate;

import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.projeto.StatusProjeto;
import com.conectsol.solarsync.projeto.TipoProjeto;

/** Projeção de listagem: sem motivo de reprova (até 1000 caracteres) nem cliente completo. */
public record ProjetoResumoResponse(
        Long id,
        Long clienteId,
        String clienteNome,
        TipoProjeto tipoProjeto,
        StatusProjeto status,
        Long analistaResponsavelId,
        String analistaResponsavelNome,
        LocalDate dataRecebimento,
        LocalDate dataEncaminhado,
        LocalDate dataAprovacao,
        LocalDate dataInstalacao) {

    public static ProjetoResumoResponse de(Projeto projeto) {
        return new ProjetoResumoResponse(
                projeto.getId(),
                projeto.getCliente().getId(),
                projeto.getCliente().getNome(),
                projeto.getTipoProjeto(),
                projeto.getStatus(),
                projeto.getAnalistaResponsavel() == null ? null
                        : projeto.getAnalistaResponsavel().getId(),
                projeto.getAnalistaResponsavel() == null ? null
                        : projeto.getAnalistaResponsavel().getNome(),
                projeto.getDataRecebimento(),
                projeto.getDataEncaminhado(),
                projeto.getDataAprovacao(),
                projeto.getDataInstalacao());
    }
}
