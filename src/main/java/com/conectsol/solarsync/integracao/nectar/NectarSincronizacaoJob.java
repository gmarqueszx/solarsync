package com.conectsol.solarsync.integracao.nectar;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Puxa periodicamente os negócios fechados do Nectar. É a entrada automática da etapa 1 do
 * fluxo, no lugar de alguém digitar o cliente que o comercial acabou de fechar.
 * <p>
 * Puxa em vez de receber webhook (decisão do usuário): funciona sem a API exposta na internet —
 * o HTTPS ainda é item pendente do checklist (seção 8, item 12) — e sem depender de alguém
 * configurar webhook no painel do Nectar. O preço é até um intervalo de atraso, que não importa
 * aqui: a etapa seguinte é humana.
 * <p>
 * O bean só existe com {@code solarsync.nectar.ativo=true}. Desligado, não há nada agendado, o
 * que mantém testes e dev local sem chamada externa.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "solarsync.nectar", name = "ativo", havingValue = "true")
class NectarSincronizacaoJob {

    private static final Logger log = LoggerFactory.getLogger(NectarSincronizacaoJob.class);

    private final NectarClient nectarClient;
    private final NectarIngestaoService ingestaoService;

    /**
     * {@code fixedDelay} e não {@code fixedRate}: conta o intervalo a partir do <i>fim</i> da
     * execução anterior, então uma sincronização lenta não enfileira outra em cima. O atraso
     * inicial evita disputar o boot com o Flyway e o resto da subida.
     */
    @Scheduled(
            fixedDelayString = "${solarsync.nectar.intervalo:PT10M}",
            initialDelayString = "PT30S")
    void sincronizar() {
        List<OportunidadeNectar> entradas;
        try {
            entradas = nectarClient.oportunidadesDeEntrada();
        } catch (RuntimeException falha) {
            // Não relança: o Nectar fora do ar, token expirado ou resposta inesperada não podem
            // encerrar o agendamento. Na próxima execução tenta de novo.
            log.error("Falha ao consultar o Nectar. Nada foi importado nesta execução.", falha);
            return;
        }

        int criados = 0;
        int ignorados = 0;
        for (OportunidadeNectar oportunidade : entradas) {
            try {
                if (ingestaoService.ingerir(oportunidade)) {
                    criados++;
                } else {
                    ignorados++;
                }
            } catch (RuntimeException falha) {
                // Por oportunidade, para que uma linha problemática no CRM não impeça as outras
                // de entrar. A transação de cada uma é separada (REQUIRES_NEW na ingestão).
                ignorados++;
                log.error("Falha ao importar a oportunidade {} do Nectar", oportunidade.id(),
                        falha);
            }
        }

        // INFO só quando houve novidade: a cada dez minutos, um INFO por execução viraria ruído
        // que esconde o que importa.
        if (criados > 0) {
            log.info("Nectar: {} oportunidade(s) nas etapas de entrada, {} cliente(s) novo(s) na "
                    + "fila da triagem, {} já conhecido(s) ou recusado(s)", entradas.size(),
                    criados, ignorados);
        } else {
            log.debug("Nectar: {} oportunidade(s) nas etapas de entrada, nenhuma nova",
                    entradas.size());
        }
    }
}
