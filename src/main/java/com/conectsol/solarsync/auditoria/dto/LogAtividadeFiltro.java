package com.conectsol.solarsync.auditoria.dto;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.conectsol.solarsync.auditoria.AcaoLog;
import com.conectsol.solarsync.common.EntidadeTipo;

/**
 * Filtros do log, todos opcionais e combinados com E. {@code de}/{@code ate} são dias inteiros
 * no fuso da operação ({@code America/Bahia}), inclusivos. {@code q} busca na descrição (o nome
 * do cliente ou do usuário afetado).
 */
public record LogAtividadeFiltro(
        Long usuarioId,
        EntidadeTipo entidadeTipo,
        Long entidadeId,
        AcaoLog acao,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
        String q) {
}
