package com.conectsol.solarsync.vistoria;

public enum StatusVistoria {
    SOLICITADA,
    APROVADA,
    REPROVADA;

    /**
     * Reprovada volta para SOLICITADA: a Coelba reprova, a equipe corrige o que foi apontado e
     * pede vistoria de novo — no mesmo registro, para o histórico mostrar quantas idas e vindas
     * aquele cliente teve.
     * <p>
     * Transição para o mesmo status não aparece aqui: o service trata como no-op idempotente.
     */
    public boolean podeIrPara(StatusVistoria destino) {
        return switch (this) {
            case SOLICITADA -> destino == APROVADA || destino == REPROVADA;
            case REPROVADA -> destino == SOLICITADA;
            case APROVADA -> false;
        };
    }
}
