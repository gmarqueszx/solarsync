package com.conectsol.solarsync.pendencia;

/**
 * Anotação de acompanhamento de quem trabalha a pendência — a coluna que a planilha tinha como
 * texto livre. <b>Não é</b> o ciclo de vida: esse é {@link StatusPendencia}, com máquina de
 * estados, evento e auditoria. Aqui não há {@code podeIrPara} nem evento, porque qualquer valor
 * pode virar qualquer outro e nenhum deles muda o fluxo nem entra em métrica.
 */
public enum StatusAcompanhamento {
    PENDENTE,
    DEBITO,
    ACOMPANHAR,
    PERIODO_DE_LEITURA,
    AGUARDO_ACOMPANHAMENTO,
    STAND_BY,
    PROJETO_CANCELADO,
    B_OPTANTE,
    SOLICITACAO_CANCELAMENTO,
    RESOLVER_NA_COELBA,
    FOI_SEM_BENEFICIARIO,
    NOTA_RETIDA,
    OBRA,
    PAGO_AG_DEBITAR
}
