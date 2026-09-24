package com.conectsol.solarsync.integracao.gmail;

/**
 * O que a integração fez com um e-mail da Coelba. Gravado em {@code email_coelba.resultado}.
 * <p>
 * Existe com esta granularidade porque o status do projeto muda sozinho e não há tela: quando
 * alguém perguntar "por que este projeto foi reprovado ontem" ou "por que este não foi", a
 * resposta tem de estar numa consulta, não numa dedução. Cada valor aponta para uma causa
 * diferente e uma correção diferente.
 */
public enum ResultadoProcessamento {

    /** Casou com um projeto e o status mudou. */
    APLICADO,

    /**
     * Teria sido {@link #APLICADO}, mas {@code solarsync.gmail.somente-conferencia} estava
     * ligado: a leitura casou com um projeto e o status <b>não</b> mudou. O {@code detalhe} diz
     * qual mudança teria acontecido.
     * <p>
     * É o valor que se olha antes de ligar a integração para valer. Por isso ele
     * <b>não conta como processado</b> em {@code EmailCoelbaRepository.idsJaProcessados}:
     * desligado o modo conferência, estes e-mails voltam a ser lidos e aí sim aplicados. Fosse o
     * contrário, o ensaio consumiria em silêncio justamente os e-mails que importavam.
     */
    CONFERENCIA,

    /**
     * Casou, mas não havia o que mudar: era acompanhamento ("em análise"), ou o projeto já
     * estava no status que o e-mail anuncia — releitura de um e-mail antigo, por exemplo.
     */
    SEM_ALTERACAO,

    /**
     * O texto não permitiu concluir aprovação nem reprovação. É o caso a acompanhar: ou é
     * e-mail de outro assunto que a consulta do Gmail deixou passar, ou a Coelba mudou a
     * redação e as listas de termos de {@code solarsync.coelba} precisam de ajuste.
     */
    NAO_RECONHECIDO,

    /** Entendeu o resultado, mas nenhum projeto tem os números encontrados no texto. */
    SEM_CORRESPONDENCIA,

    /**
     * Mais de um projeto candidato, ou o e-mail afirmando aprovação e reprovação ao mesmo tempo.
     * Nada é aplicado: escolher no escuro é pior que não agir.
     */
    AMBIGUO,

    /**
     * Casou, mas a máquina de estados barrou — e-mail aprovando um projeto que nunca foi
     * encaminhado, por exemplo. A guarda funcionou: é ela que impede o dashboard de calcular
     * tempo até aprovação sobre uma data de envio nula.
     */
    TRANSICAO_INVALIDA,

    /** Falha inesperada ao processar. Detalhe no log e na coluna {@code detalhe}. */
    ERRO
}
