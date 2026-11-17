package com.conectsol.solarsync.auditoria;

/** O que aconteceu com o registro. A mudança de status é uma {@code EDICAO} do campo status. */
public enum AcaoLog {
    CRIACAO,
    EDICAO,
    EXCLUSAO
}
