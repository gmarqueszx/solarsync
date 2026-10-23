package com.conectsol.solarsync.integracao.gmail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.conectsol.solarsync.projeto.ProjetoRepository;

/**
 * A busca sem limite de data do job (22/10/2026). Caso real: a solicitação 2610021352 foi
 * aprovada na Coelba em 07/10 e só marcada como encaminhada aqui em 22/10 — a consulta normal,
 * de sete dias, nunca viu a aprovação.
 * <p>
 * Com dublês porque o job só existe com {@code solarsync.gmail.ativo=true}, que a suíte desliga
 * (seção 11 do CLAUDE.md: "nenhum job é testado ligado").
 */
class RetornoCoelbaJobBuscaRetroativaTest {

    private GmailClient gmail;
    private EmailCoelbaRepository emails;
    private RetornoCoelbaService servico;
    private ProjetoRepository projetos;
    private RetornoCoelbaJob job;

    @BeforeEach
    void preparar() {
        gmail = mock(GmailClient.class);
        emails = mock(EmailCoelbaRepository.class);
        servico = mock(RetornoCoelbaService.class);
        projetos = mock(ProjetoRepository.class);
        job = new RetornoCoelbaJob(gmail, emails, servico,
                new GmailProperties(true, false, true, 0, "id", "segredo", "refresh",
                        null, null, 0, null),
                projetos);

        when(projetos.numerosAguardandoRetornoDaCoelba()).thenReturn(List.of("2610021352"));
        when(emails.idsJaProcessados(anyList())).thenReturn(List.of());
        when(servico.processar(any(), anyBoolean())).thenReturn(ResultadoProcessamento.SEM_ALTERACAO);
        for (String id : List.of("aprovacao", "analise", "documentacao", "vistoria-aprovada")) {
            when(gmail.buscar(id)).thenReturn(new MensagemGmail(id, null, "", null));
        }
    }

    /**
     * A retroativa traz o histórico inteiro do número; a normal, só o que cabe nos sete dias. O
     * e-mail que aparece nas duas é processado uma vez, e o conjunto sai do mais antigo para o
     * mais novo — aplicar a aprovação antes da documentação deixaria o projeto para trás.
     */
    @Test
    void encaminhadoHaPoucoTemOHistoricoInteiroProcessadoEmOrdemCronologica() {
        when(projetos.numerosEncaminhadosDesde(any())).thenReturn(List.of("2610021352"));
        // Ordem do Gmail: do mais novo para o mais antigo.
        when(gmail.listarIdsSemLimiteDeData(List.of("2610021352")))
                .thenReturn(List.of("aprovacao", "analise", "documentacao"));
        when(gmail.listarIdsDosProjetos(List.of("2610021352"))).thenReturn(List.of("aprovacao"));

        job.lerRetornos();

        InOrder ordem = inOrder(gmail);
        ordem.verify(gmail).buscar("documentacao");
        ordem.verify(gmail).buscar("analise");
        ordem.verify(gmail).buscar("aprovacao");
        verify(gmail, times(3)).buscar(any());
    }

    /**
     * Vistoria lançada há pouco: o histórico inteiro da solicitação volta, mas o que só a busca
     * da vistoria trouxe é lido só para a vistoria — um cancelamento antigo não pode reprovar o
     * projeto já aprovado. O que a consulta normal também trouxe é lido por inteiro.
     */
    @Test
    void vistoriaLancadaHaPoucoRelêOHistoricoSoParaAVistoria() {
        when(projetos.numerosEncaminhadosDesde(any())).thenReturn(List.of());
        when(projetos.numerosComVistoriaLancadaDesde(any())).thenReturn(List.of("2610021352"));
        when(gmail.listarIdsSemLimiteDeData(List.of("2610021352")))
                .thenReturn(List.of("vistoria-aprovada", "aprovacao"));
        // Só o aviso de vistoria, recente, também cabe na janela de sete dias da consulta normal.
        when(gmail.listarIdsDosProjetos(List.of("2610021352")))
                .thenReturn(List.of("vistoria-aprovada"));

        job.lerRetornos();

        verify(servico).processar(new MensagemGmail("aprovacao", null, "", null), true);
        verify(servico).processar(new MensagemGmail("vistoria-aprovada", null, "", null), false);
        verify(servico, times(2)).processar(any(), anyBoolean());
    }

    @Test
    void semProjetoEncaminhadoHaPoucoNaoHaBuscaSemLimiteDeData() {
        when(projetos.numerosEncaminhadosDesde(any())).thenReturn(List.of());
        when(gmail.listarIdsDosProjetos(anyList())).thenReturn(List.of());

        job.lerRetornos();

        verify(gmail, never()).listarIdsSemLimiteDeData(anyList());
    }
}
