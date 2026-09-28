package com.conectsol.solarsync.auth.event;

import java.time.Instant;

import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.common.event.EntidadeStatusEvent;

/**
 * Uma operação da gestão de usuários, para o {@code historico_status} responder "quem redefiniu
 * a senha de quem" — antes não havia registro nenhum, e um GESTOR podia assumir a conta de outro
 * sem deixar rastro (achado A-01 da auditoria de segurança).
 * <p>
 * Não é uma máquina de estados: {@code statusNovo} é o que aconteceu ({@code ATIVO},
 * {@code INATIVO}, {@code SENHA_DEFINIDA}, {@code EMAIL_ALTERADO}) ou, na criação e na troca de
 * papéis, a lista de papéis resultante. Os valores cabem nos 30 caracteres da coluna: a lista
 * mais longa possível, {@code ADMINISTRADOR,ANALISTA,GESTOR}, tem 29.
 *
 * @param alvoId  o usuário alterado
 * @param autorId quem fez a alteração
 */
public record UsuarioAlteradoEvent(
        Long alvoId,
        String statusAnterior,
        String statusNovo,
        Instant ocorridoEm,
        Long autorId) implements EntidadeStatusEvent {

    @Override
    public EntidadeTipo getEntidadeTipo() {
        return EntidadeTipo.USUARIO;
    }

    @Override
    public Long getEntidadeId() {
        return alvoId;
    }

    @Override
    public String getStatusAnterior() {
        return statusAnterior;
    }

    @Override
    public String getStatusNovo() {
        return statusNovo;
    }

    @Override
    public Instant getOcorridoEm() {
        return ocorridoEm;
    }

    @Override
    public Long getUsuarioId() {
        return autorId;
    }
}
