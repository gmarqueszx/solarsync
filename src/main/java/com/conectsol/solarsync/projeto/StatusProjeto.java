package com.conectsol.solarsync.projeto;

public enum StatusProjeto {
    /** Analista recebeu o projeto. Criado automaticamente quando a pendência é resolvida. */
    RECEBIDO,
    /**
     * Projeto preenchido, mas ainda não enviado à Coelba por algum motivo operacional. Não é o
     * estado do cliente devedor: débito não pausa o projeto, ele impede o envio — ver
     * {@code ProjetoService.exigirClienteSemDebito}.
     */
    AGUARDANDO_ENVIO,
    ENCAMINHADO,
    APROVADO,
    REPROVADO,
    REENCAMINHADO;

    /**
     * Transições permitidas. Pular {@code AGUARDANDO_ENVIO} é o caminho normal — aquele estado
     * só aparece quando algo atrasa o envio. {@code APROVADO → REPROVADO} existe porque a
     * Coelba revisa decisão.
     * <p>
     * O que a guarda impede é o que quebra o dashboard: aprovar um projeto que nunca foi
     * encaminhado gravaria {@code data_aprovacao} com {@code data_encaminhado} nulo, e a
     * métrica de tempo até aprovação sairia absurda.
     */
    public boolean podeIrPara(StatusProjeto destino) {
        return switch (this) {
            case RECEBIDO -> destino == AGUARDANDO_ENVIO || destino == ENCAMINHADO;
            case AGUARDANDO_ENVIO -> destino == ENCAMINHADO || destino == RECEBIDO;
            case ENCAMINHADO -> destino == APROVADO || destino == REPROVADO;
            case REPROVADO -> destino == REENCAMINHADO || destino == AGUARDANDO_ENVIO;
            case REENCAMINHADO -> destino == APROVADO || destino == REPROVADO;
            case APROVADO -> destino == REPROVADO;
        };
    }
}
