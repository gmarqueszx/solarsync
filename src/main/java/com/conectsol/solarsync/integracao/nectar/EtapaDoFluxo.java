package com.conectsol.solarsync.integracao.nectar;

import com.conectsol.solarsync.pendencia.StatusPendencia;
import com.conectsol.solarsync.projeto.StatusProjeto;
import com.conectsol.solarsync.vistoria.StatusVistoria;

/**
 * As situações do fluxo do SolarSync que têm etapa correspondente no Nectar.
 * <p>
 * <b>Não é uma cópia dos status do domínio</b>, e é de propósito: o CRM acompanha a gestão do
 * cliente, não a máquina de estados da homologação. {@code RECEBIDO} e {@code AGUARDANDO_ENVIO}
 * são situações diferentes aqui dentro (um projeto atrasado por motivo operacional não é um
 * projeto recém-chegado) e a mesma coisa lá fora — "projeto para fazer". Já {@code SOLICITADA} na
 * vistoria vira duas, porque o Nectar distingue a primeira solicitação da que vem depois de uma
 * reprova, e é essa distinção que conta a história da obra para o comercial.
 * <p>
 * Esta camada existe justamente para absorver essa diferença: sem ela, o mapa de configuração
 * teria de repetir status e o código teria {@code if} de status espalhado.
 */
public enum EtapaDoFluxo {

    /** Pendência aberta na Coelba: o projeto ainda não pode ser feito. */
    PENDENCIA_ABERTA(Funil.PROJETOS),

    /** Pendência resolvida (ou nunca houve): o projeto está com o analista, à espera de envio. */
    PROJETO_PARA_FAZER(Funil.PROJETOS),

    PROJETO_ENCAMINHADO(Funil.PROJETOS),

    /**
     * A única situação cuja etapa muda entre o fluxo normal e o Banco: no normal o cliente vai
     * para "aguardando instalação", no Banco para "aprovado sem pagamento", porque ali o que o
     * comercial ainda acompanha é o financiamento.
     */
    PROJETO_APROVADO(Funil.PROJETOS),

    PROJETO_REPROVADO(Funil.PROJETOS),

    /** Projeto retificado e encaminhado novamente. */
    PROJETO_REENCAMINHADO(Funil.PROJETOS),

    VISTORIA_SOLICITADA(Funil.INSTALACAO),

    VISTORIA_APROVADA(Funil.INSTALACAO),

    VISTORIA_REPROVADA(Funil.INSTALACAO),

    /** Corrigido o que a vistoria apontou, ela é solicitada de novo — no mesmo registro daqui. */
    VISTORIA_RESOLICITADA(Funil.INSTALACAO);

    /**
     * Em qual dos dois funis do Nectar a etapa vive. O setor de Projetos acompanha o cliente no
     * "6- Projetos" até a homologação sair, e no "7- Instalação" daí em diante.
     */
    public enum Funil {
        PROJETOS,
        INSTALACAO
    }

    private final Funil funil;

    EtapaDoFluxo(Funil funil) {
        this.funil = funil;
    }

    public Funil funil() {
        return funil;
    }

    /**
     * A pendência só move o cliente quando é <b>aberta</b> (ou reaberta). Resolvê-la não tem etapa
     * própria: quem move o cliente para "projeto para fazer" é o projeto que nasce em seguida, e
     * mandar as duas movimentações seria duas chamadas ao CRM para o mesmo instante do fluxo — com
     * a segunda desfazendo a primeira em ordem indeterminada.
     * <p>
     * Cancelada também não move: a pendência apontada por engano não devia ter tirado o cliente do
     * lugar, e a etapa certa depois disso depende de haver ou não projeto — quem sabe isso é a
     * transição do projeto, que virá.
     */
    static EtapaDoFluxo de(StatusPendencia status) {
        return status == StatusPendencia.ABERTA ? PENDENCIA_ABERTA : null;
    }

    static EtapaDoFluxo de(StatusProjeto status) {
        return switch (status) {
            case RECEBIDO, AGUARDANDO_ENVIO -> PROJETO_PARA_FAZER;
            case ENCAMINHADO -> PROJETO_ENCAMINHADO;
            case APROVADO -> PROJETO_APROVADO;
            case REPROVADO -> PROJETO_REPROVADO;
            case REENCAMINHADO -> PROJETO_REENCAMINHADO;
        };
    }

    /**
     * O status anterior é o que distingue a primeira solicitação da que vem depois de uma reprova
     * — a vistoria reaproveita o mesmo registro nas duas, então o status novo sozinho não conta a
     * diferença que o Nectar quer mostrar.
     */
    static EtapaDoFluxo de(StatusVistoria anterior, StatusVistoria novo) {
        return switch (novo) {
            case SOLICITADA -> anterior == StatusVistoria.REPROVADA
                    ? VISTORIA_RESOLICITADA
                    : VISTORIA_SOLICITADA;
            case APROVADA -> VISTORIA_APROVADA;
            case REPROVADA -> VISTORIA_REPROVADA;
        };
    }
}
