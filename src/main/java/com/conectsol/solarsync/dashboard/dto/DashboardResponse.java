package com.conectsol.solarsync.dashboard.dto;

import java.time.LocalDate;

/**
 * As 13 métricas da seção 5 do CLAUDE.md numa resposta só — a tela mostra todas juntas, e
 * dividir em 13 rotas faria o frontend orquestrar 13 chamadas para montar uma página.
 * <p>
 * <b>Tempos são médias em dias, e podem ser nulos</b>: nulo significa "não houve caso no
 * período", que é diferente de zero. Devolver 0 faria o gestor ler "instantâneo" onde na
 * verdade não há dado.
 * <p>
 * Cada métrica é filtrada pela <b>sua própria data de referência</b> — projetos aprovados pela
 * data de aprovação, pendências resolvidas pela data de resolução, e assim por diante. É o que
 * responde "no período X, como foi o desempenho", em vez de misturar recortes.
 */
public record DashboardResponse(
        Periodo periodo,
        Filtro filtro,
        TemposMediosEmDias temposMediosEmDias,
        Quantitativos quantitativos) {

    /** Limites aplicados; nulo em qualquer ponta significa "sem limite daquele lado". */
    public record Periodo(LocalDate de, LocalDate ate) {
    }

    /**
     * Recortes que não são de data. Hoje só o analista: nulo é a equipe inteira, e é o padrão.
     * <p>
     * Volta na resposta para a tela poder rotular os números com o recorte a que pertencem — "3
     * projetos aprovados" diz coisas bem diferentes com e sem filtro de pessoa. O nome vem
     * junto do id para a tela não precisar cruzar com a lista de usuários só para escrever um
     * cabeçalho.
     */
    public record Filtro(Long analistaId, String analistaNome) {
    }

    public record TemposMediosEmDias(
            /** Do pagamento do cliente até a primeira vez que alguém mexeu nele no sistema. */
            Double semNinguemMexerNoCliente,
            /** Da abertura da pendência até a resolução. */
            Double resolucaoDePendencia,
            /** Do recebimento do projeto até o envio à Coelba. */
            Double recebimentoAteEnvio,
            /** Do envio à Coelba até a aprovação. */
            Double envioAteAprovacao,
            /** Da detecção do débito até a quitação. */
            Double paradoPorDebito,
            /** Da instalação da usina até a solicitação da vistoria. */
            Double instalacaoAteSolicitarVistoria,
            /** Da solicitação do desligamento do medidor unificado até ele ser desligado. */
            Double esperaDoDesligamento,
            /** Do recebimento do projeto até a vistoria aprovada: o ciclo inteiro do cliente. */
            Double cicloCompleto) {
    }

    public record Quantitativos(
            long pendenciasAbertasNoPeriodo,
            long pendenciasResolvidas,
            long projetosEncaminhados,
            long projetosReencaminhados,
            long projetosAprovados,
            long projetosReprovados,
            /** Situação de agora, não do período: quem está travado neste momento. */
            long clientesComDebitoAtivo,
            /** Dos travados, quantos não conseguem nem resolver a pendência na Coelba. */
            long clientesTravadosNaPendencia,
            /** Dos travados, quantos estão com o projeto pronto e o envio bloqueado. */
            long clientesTravadosNaHomologacao,
            /** Também situação de agora, para dar denominador aos números acima. */
            long clientesComDebitoQuitado,
            long vistoriasSolicitadas,
            long vistoriasAprovadas,
            long vistoriasReprovadas,
            /** Situação de agora: unificações ainda por fazer. */
            long unificacoesPendentes,
            /** Situação de agora: desligamento pedido e ainda sem retorno da equipe de campo. */
            long desligamentosAguardando,
            /** Situação de agora: casos que precisaram de ordem de serviço. */
            long desligamentosComOsAberta,
            long desligamentosConcluidos) {
    }
}
