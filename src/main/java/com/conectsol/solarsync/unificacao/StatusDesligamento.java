package com.conectsol.solarsync.unificacao;

/**
 * Situação do desligamento do medidor unificado.
 * <p>
 * O ciclo é solicitar e aguardar retorno, não um liga-desliga: terminada a instalação, confere-se
 * se a unificação foi feita e, se sim, pede-se o desligamento do medidor unificado. Quando a
 * equipe de campo não realiza o desligamento, abre-se O.S. — um desvio do caminho normal, que
 * precisa aparecer para o caso não ficar parado como se ainda estivesse só aguardando.
 */
public enum StatusDesligamento {
    NAO_SOLICITADO,
    /** Pedido feito, aguardando a equipe de campo. É aqui que o caso costuma se perder. */
    SOLICITADO,
    /** A equipe de campo não realizou o desligamento e foi aberta ordem de serviço. */
    OS_ABERTA,
    CONCLUIDO;

    public boolean podeIrPara(StatusDesligamento destino) {
        return switch (this) {
            case NAO_SOLICITADO -> destino == SOLICITADO;
            case SOLICITADO -> destino == CONCLUIDO || destino == OS_ABERTA;
            case OS_ABERTA -> destino == CONCLUIDO;
            case CONCLUIDO -> false;
        };
    }
}
