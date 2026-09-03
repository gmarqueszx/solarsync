package com.conectsol.solarsync.debito;

/**
 * Situação do débito do cliente na Coelba, conforme a última consulta feita pelo analista na
 * agência virtual.
 * <p>
 * Sem máquina de estados aqui, ao contrário de Pendencia e Projeto: com dois estados e as duas
 * transições legítimas (o cliente quita, e depois pode voltar a dever), uma guarda
 * {@code podeIrPara} não barraria nada — seria cerimônia sem valor.
 */
public enum StatusDebito {
    ATIVO,
    QUITADO
}
