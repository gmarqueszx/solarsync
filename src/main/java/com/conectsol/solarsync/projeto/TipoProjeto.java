package com.conectsol.solarsync.projeto;

/**
 * Os três subtipos que existem na operação (confirmado com o usuário).
 * <p>
 * A planilha tinha seis abas de subtipo ("aumento de potência", "uma placa a mais", "mudança de
 * inversor 5kW", "inversores separados"), mas elas separavam <b>casos operacionais</b>, não
 * tipos de projeto: todas percorrem o mesmo ciclo de vida e todas são, do ponto de vista da
 * Coelba, ou uma ampliação da usina existente ou uma correção de projeto já enviado.
 */
public enum TipoProjeto {

    /** Primeira homologação da usina do cliente. */
    PROJETO_INICIAL,

    /** Aumenta a usina já homologada — mais placas, mais potência, outro inversor somado. */
    AMPLIACAO,

    /** Corrige um projeto já enviado à Coelba. */
    CORRECAO
}
