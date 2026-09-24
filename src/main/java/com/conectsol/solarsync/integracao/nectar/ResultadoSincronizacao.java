package com.conectsol.solarsync.integracao.nectar;

/**
 * O que aconteceu numa tentativa de mover o cliente de etapa no Nectar. Seis valores porque cada
 * um aponta para uma causa e uma correção diferentes — a mesma disciplina do
 * {@code ResultadoProcessamento} da leitura do e-mail da Coelba.
 */
public enum ResultadoSincronizacao {

    /** O PUT foi feito e o CRM aceitou. */
    APLICADO,

    /**
     * Modo ensaio ({@code solarsync.nectar.saida.somente-conferencia}): teria movido, e não moveu.
     * Como o {@code CONFERENCIA} do e-mail da Coelba, <b>não</b> conta como aplicado para efeito
     * de idempotência — desligado o modo, a próxima transição do cliente move de verdade.
     */
    CONFERENCIA,

    /** O CRM já estava na etapa certa. Registrado, e não silencioso, porque é a prova de que a
     *  idempotência funcionou — sem a linha, "não fez nada" e "não rodou" se confundem. */
    JA_NA_ETAPA,

    /**
     * Cliente cadastrado à mão, sem vínculo com o CRM. É o caso normal de metade da base, não um
     * defeito: nem todo projeto do setor nasceu de um negócio no Nectar.
     */
    SEM_OPORTUNIDADE,

    /** Situação do fluxo sem etapa correspondente configurada. Sinaliza configuração incompleta. */
    SEM_MAPEAMENTO,

    /**
     * A API do Nectar recusou ou não respondeu. É a única que entra na fila de reprocessamento —
     * e a única cuja ausência de tela custaria caro, porque o projeto anda aqui e o CRM fica para
     * trás sem ninguém ver.
     */
    ERRO;

    /**
     * Resultados que dispensam nova chamada ao CRM para a mesma etapa. {@code CONFERENCIA} fica
     * de fora de propósito (ver acima), e {@code ERRO} também — é justamente o que se quer repetir.
     */
    boolean dispensaNovaChamada() {
        return this == APLICADO || this == JA_NA_ETAPA;
    }
}
