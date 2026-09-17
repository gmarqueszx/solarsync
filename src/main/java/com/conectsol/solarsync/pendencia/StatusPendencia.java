package com.conectsol.solarsync.pendencia;

public enum StatusPendencia {
    ABERTA,
    EM_ANDAMENTO,
    RESOLVIDA,
    CANCELADA;

    /**
     * Transições permitidas. Deliberadamente permissiva: o processo real na Coelba não segue
     * ordem rígida, então a guarda existe para barrar o absurdo (que corromperia as métricas do
     * dashboard), não para impor burocracia.
     * <p>
     * Reabrir uma pendência resolvida é seguro:
     * {@code ProjetoService.criarOuAtivarProjetoParaCliente} não duplica projeto se já houver
     * um em andamento.
     * <p>
     * Transição para o mesmo status não aparece aqui: o service trata isso antes, como no-op
     * idempotente, para um duplo clique não gerar duas linhas de histórico.
     */
    public boolean podeIrPara(StatusPendencia destino) {
        return switch (this) {
            case ABERTA -> destino == EM_ANDAMENTO || destino == RESOLVIDA || destino == CANCELADA;
            case EM_ANDAMENTO -> destino == ABERTA || destino == RESOLVIDA || destino == CANCELADA;
            case RESOLVIDA -> destino == ABERTA || destino == EM_ANDAMENTO;
            case CANCELADA -> destino == ABERTA;
        };
    }
}
