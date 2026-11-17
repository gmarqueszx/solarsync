package com.conectsol.solarsync.auditoria.dto;

/**
 * Um campo que mudou. {@code campo} é o nome da propriedade na entidade (camelCase); o rótulo em
 * português é da tela. Os valores já vêm formatados como texto: enum pelo nome, data em ISO,
 * referência a cliente ou usuário pelo nome.
 */
public record Alteracao(String campo, String anterior, String novo) {
}
