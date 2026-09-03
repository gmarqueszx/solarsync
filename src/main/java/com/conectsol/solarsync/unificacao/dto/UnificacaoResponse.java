package com.conectsol.solarsync.unificacao.dto;

import java.time.Instant;

import com.conectsol.solarsync.auth.dto.UsuarioResumoResponse;
import com.conectsol.solarsync.cliente.dto.ClienteResumoResponse;
import com.conectsol.solarsync.unificacao.Unificacao;

public record UnificacaoResponse(
        Long id,
        ClienteResumoResponse cliente,
        String cidade,
        UsuarioResumoResponse projetista,
        String informacoes,
        boolean feita,
        boolean desligamento,
        Instant criadoEm,
        Instant atualizadoEm) {

    public static UnificacaoResponse de(Unificacao unificacao) {
        return new UnificacaoResponse(
                unificacao.getId(),
                ClienteResumoResponse.de(unificacao.getCliente()),
                unificacao.getCidade(),
                UsuarioResumoResponse.de(unificacao.getProjetista()),
                unificacao.getInformacoes(),
                unificacao.isFeita(),
                unificacao.isDesligamento(),
                unificacao.getCriadoEm(),
                unificacao.getAtualizadoEm());
    }
}
