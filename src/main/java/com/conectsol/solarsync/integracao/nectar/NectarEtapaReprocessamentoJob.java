package com.conectsol.solarsync.integracao.nectar;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Tenta de novo as movimentações que o Nectar recusou.
 * <p>
 * Existe porque, sem ele, uma falha só se corrigiria sozinha no dia em que aquele cliente mudasse
 * de status de novo — e há status que são o último do fluxo. O CRM ficaria para trás para sempre,
 * com a trilha registrando corretamente que ficou e ninguém olhando.
 * <p>
 * <b>Só reprocessa a falha que ainda é a última palavra sobre o cliente</b>
 * ({@code falhasPendentes}). Um projeto que falhou ao ir para "encaminhado" e desde então foi
 * aprovado já tem uma linha mais nova; repetir a antiga empurraria o cliente de volta no CRM, e a
 * integração passaria a mentir com a melhor das intenções.
 * <p>
 * Não é um mecanismo paralelo de retry: é a mesma trilha que a auditoria usa, lida de outro
 * ângulo. Um cliente que continua falhando gera uma linha de ERRO por execução, o que é a
 * medida de quanto tempo o CRM está fora — não ruído.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = { "solarsync.nectar.ativo", "solarsync.nectar.saida.ativo" },
        havingValue = "true")
class NectarEtapaReprocessamentoJob {

    private static final Logger log =
            LoggerFactory.getLogger(NectarEtapaReprocessamentoJob.class);

    private final NectarEtapaSincronizacaoRepository sincronizacaoRepository;
    private final NectarEtapaService etapaService;
    private final NectarSaidaProperties propriedades;

    /**
     * {@code fixedDelay} pelo mesmo motivo do job de entrada: conta o intervalo a partir do fim da
     * execução anterior, então um CRM lento não enfileira uma execução sobre a outra. O atraso
     * inicial é maior que o do job de entrada para as duas não disputarem a subida.
     */
    @Scheduled(
            fixedDelayString = "${solarsync.nectar.saida.intervalo-reprocessamento:PT30M}",
            initialDelayString = "PT2M")
    void reprocessar() {
        List<NectarEtapaSincronizacao> pendentes = sincronizacaoRepository.falhasPendentes(
                PageRequest.of(0, propriedades.maximoPorReprocessamento()));

        if (pendentes.isEmpty()) {
            return;
        }

        log.info("Nectar: retomando {} sincronização(ões) de etapa que haviam falhado",
                pendentes.size());

        for (NectarEtapaSincronizacao falha : pendentes) {
            try {
                // Cada uma abre a própria transação (REQUIRES_NEW no service), então uma que volte
                // a falhar não impede as outras — mesma disciplina da ingestão de oportunidades.
                etapaService.sincronizar(falha.getClienteId(), falha.getEtapaFluxo());
            } catch (RuntimeException erro) {
                log.error("Falha ao retomar a sincronização do cliente {}", falha.getClienteId(),
                        erro);
            }
        }
    }
}
