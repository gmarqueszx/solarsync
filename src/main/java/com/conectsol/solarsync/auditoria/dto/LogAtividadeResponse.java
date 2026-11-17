package com.conectsol.solarsync.auditoria.dto;

import java.time.Instant;
import java.util.List;

import com.conectsol.solarsync.auditoria.AcaoLog;
import com.conectsol.solarsync.common.EntidadeTipo;

/**
 * Uma linha do log. {@code usuarioId} nulo é ação do sistema — as integrações (Nectar, Gmail) e
 * o bootstrap rodam sem usuário logado. {@code usuarioNome} é o nome de quando a ação aconteceu,
 * não o atual: o log continua legível mesmo depois de o usuário ser renomeado ou excluído.
 */
public record LogAtividadeResponse(
        Long id,
        Instant ocorridoEm,
        Long usuarioId,
        String usuarioNome,
        AcaoLog acao,
        EntidadeTipo entidadeTipo,
        Long entidadeId,
        String descricao,
        List<Alteracao> alteracoes) {
}
