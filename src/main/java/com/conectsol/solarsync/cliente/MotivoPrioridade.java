package com.conectsol.solarsync.cliente;

/**
 * Por que este cliente foi posto na frente dos outros. É obrigatório sempre que a prioridade
 * está ligada: prioridade sem motivo não dá para revisar depois — ninguém sabe se ainda vale.
 * <p>
 * Não é máquina de estados nem tem {@code podeIrPara}, pela mesma razão do {@link StatusTriagem}:
 * qualquer motivo pode virar qualquer outro (o prazo do contrato muda, a instalação é
 * antecipada), e não sobra transição absurda para barrar.
 */
public enum MotivoPrioridade {

    /**
     * A instalação do cliente foi adiantada — a usina já está montada e o que falta é a
     * homologação correr atrás. É o único motivo que exige data: é ela que a etapa de vistoria
     * vai usar, e sem ela a prioridade por instalação não teria como se completar no fim do
     * fluxo.
     */
    INSTALACAO_ADIANTADA,

    /** O contrato prevê um prazo de instalação menor que o normal. */
    PRAZO_CONTRATUAL,

    /** O cliente tem um prazo próprio a cumprir (obra, mudança, safra). */
    PRAZO_DO_CLIENTE,

    /** Situação excepcional; o que aconteceu fica na observação. */
    OUTRO;

    /**
     * Motivo ligado à instalação, que é o que torna a data obrigatória e a faz descer para o
     * projeto. Método, e não comparação solta espalhada pelo código, porque a regra aparece em
     * três lugares (validação, propagação para o projeto e a tela) e precisa ser a mesma.
     */
    public boolean exigeDataDeInstalacao() {
        return this == INSTALACAO_ADIANTADA;
    }
}
