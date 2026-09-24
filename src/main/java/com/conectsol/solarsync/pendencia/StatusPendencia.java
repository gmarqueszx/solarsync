package com.conectsol.solarsync.pendencia;

public enum StatusPendencia {
    /**
     * Único estado ativo: a solicitação está correndo na Coelba. Apontar a pendência na triagem
     * <b>é</b> iniciá-la — não existe pendência identificada que ainda não foi solicitada, então
     * o antigo {@code EM_ANDAMENTO} (e o endpoint {@code /iniciar} que levava até ele) era um
     * clique a mais que não mudava nada: o relógio da métrica sempre saiu de
     * {@code solicitado_em}, gravado na criação.
     */
    ABERTA,
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
            case ABERTA -> destino == RESOLVIDA || destino == CANCELADA;
            case RESOLVIDA, CANCELADA -> destino == ABERTA;
        };
    }
}
