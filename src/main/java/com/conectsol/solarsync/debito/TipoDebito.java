package com.conectsol.solarsync.debito;

/**
 * Que etapa do fluxo o débito trava — não a natureza da dívida.
 * <p>
 * A mesma agência virtual da Coelba responde duas perguntas diferentes em momentos diferentes:
 * na etapa 1, se há débito impedindo a Coelba de resolver a pendência (troca de titularidade,
 * ligação nova, ...); na etapa 2/3, se há débito impedindo a homologação do projeto. São
 * consultadas por pessoas diferentes — a segunda pelo projetista, ao receber o cliente com a
 * pendência já concluída — e podem estar em situações diferentes ao mesmo tempo, inclusive
 * porque o cliente pode ter mais de uma UC (é disso que trata a unificação).
 * <p>
 * Sem {@code podeIrPara}: não há transição entre tipos. O tipo é a identidade do registro,
 * junto com o cliente (constraint {@code uk_debito_cliente_tipo}).
 */
public enum TipoDebito {

    /** Trava a resolução da pendência na Coelba (etapa 1). */
    PENDENCIA,

    /** Trava o envio do projeto à Coelba para homologação (etapa 2/3). */
    HOMOLOGACAO
}
