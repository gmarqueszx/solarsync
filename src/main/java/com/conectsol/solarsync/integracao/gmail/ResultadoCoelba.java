package com.conectsol.solarsync.integracao.gmail;

/**
 * O que o e-mail do Portal da Geração Distribuída está dizendo sobre um projeto.
 * <p>
 * Cobre as etapas 3 <b>e 4</b> do fluxo (seção 1 do CLAUDE.md): o portal notifica o ciclo
 * inteiro, da análise técnica até a conexão, então o mesmo parser que aprova o projeto também
 * sabe dizer que a vistoria foi realizada. O que decide qual etapa vira qual resultado é
 * {@link CoelbaProperties}, e o mapa saiu do fluxo real reconstruído em 19/09/2026.
 */
public enum ResultadoCoelba {

    /** A Coelba homologou. Leva o projeto a APROVADO, com a data do e-mail. */
    APROVADO,

    /** A Coelba recusou. Leva o projeto a REPROVADO, com o motivo lido do texto. */
    REPROVADO,

    /**
     * A Coelba está realizando a vistoria — ou seja, ela foi solicitada de fato. Só <b>avança</b>
     * uma vistoria existente (decisão do usuário em 19/09/2026); nunca cria, porque criar exigiria
     * a data de instalação, que é evento de campo e não existe no portal.
     */
    VISTORIA_SOLICITADA,

    /**
     * Ponto de conexão aprovado: a vistoria passou. Vem <b>depois</b> de "realizando vistoria" —
     * quem desempatou foi a {@code Data limite} de cada e-mail, já que o portal manda as
     * notificações finais fora de ordem e com {@code Etapa anterior} inconfiável.
     */
    VISTORIA_APROVADA,

    /**
     * Acompanhamento sem decisão. Não muda nada, e é o correto: projeto em análise já está
     * ENCAMINHADO. Existe como valor próprio para o registro dizer "era só acompanhamento" em
     * vez de "não entendi o e-mail" — a primeira não precisa de ação nenhuma, a segunda sim.
     */
    EM_ANALISE
}
