package com.conectsol.solarsync.integracao.nectar;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.ClienteRepository;

import lombok.RequiredArgsConstructor;

/**
 * Põe o cliente na etapa do Nectar que corresponde ao ponto em que o projeto dele está aqui
 * dentro. É a integração de <b>saída</b> prevista na seção 9 do CLAUDE.md, e a que o desenho de
 * eventos deixou mais barata: nada em {@code pendencia}, {@code projeto} ou {@code vistoria}
 * precisou mudar.
 * <p>
 * <b>Nunca desfaz nada aqui dentro.</b> O status no SolarSync já está gravado e commitado quando
 * este código roda (o listener é {@code AFTER_COMMIT}); se o CRM recusar, o projeto continua onde
 * deve e a falha vira uma linha em {@code nectar_etapa_sincronizacao} — que é a fila do
 * reprocessamento e a única resposta possível para "por que o cliente está na etapa errada lá?".
 * <p>
 * <b>Duas camadas de idempotência</b>, e as duas existem por razões diferentes:
 * <ol>
 *   <li>a trilha local, que evita a viagem quando já pusemos o cliente naquela etapa;</li>
 *   <li>o próprio CRM, consultado antes do {@code PUT} — é a que vale quando alguém arrastou o
 *       card no painel, ou quando o banco daqui foi recriado.</li>
 * </ol>
 * Só a segunda é verdade; a primeira é economia.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = { "solarsync.nectar.ativo", "solarsync.nectar.saida.ativo" },
        havingValue = "true")
public class NectarEtapaService {

    private static final Logger log = LoggerFactory.getLogger(NectarEtapaService.class);

    private final ClienteRepository clienteRepository;
    private final NectarEtapaSincronizacaoRepository sincronizacaoRepository;
    private final NectarClient nectarClient;
    private final NectarSaidaProperties propriedades;

    /**
     * {@code REQUIRES_NEW} porque quem chama é um {@code @TransactionalEventListener} em
     * {@code AFTER_COMMIT}: ali a transação original ainda está ligada à thread, e um
     * {@code @Transactional} comum entraria nela — já commitada —, fazendo o insert da trilha ser
     * <b>descartado em silêncio</b>. É a armadilha que a seção 3 do CLAUDE.md registra, e que já
     * custou um bug aqui.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sincronizar(Long clienteId, EtapaDoFluxo fluxo) {
        if (clienteId == null || fluxo == null) {
            return;
        }

        Cliente cliente = clienteRepository.findById(clienteId).orElse(null);
        if (cliente == null) {
            return;
        }

        String oportunidadeId = cliente.getNectarOportunidadeId();
        boolean banco = cliente.isBanco();

        if (oportunidadeId == null) {
            // Metade da base entra pela tela, não pelo CRM. Não é defeito, e por isso é DEBUG:
            // um WARN por transição de cliente manual afogaria o log de quem procura falha real.
            registrar(clienteId, null, fluxo, banco, null, null,
                    ResultadoSincronizacao.SEM_OPORTUNIDADE,
                    "Cliente sem vínculo com o Nectar (cadastro manual)");
            return;
        }

        Long etapaId = propriedades.etapaId(fluxo, banco);
        if (etapaId == null) {
            log.warn("Sem etapa configurada no Nectar para {} (banco={}). Confira "
                    + "solarsync.nectar.saida.etapas.", fluxo, banco);
            registrar(clienteId, oportunidadeId, fluxo, banco, null, null,
                    ResultadoSincronizacao.SEM_MAPEAMENTO,
                    "Nenhuma etapa configurada para " + fluxo);
            return;
        }

        long funilId = propriedades.funilId(fluxo);

        if (jaAplicamos(clienteId, etapaId)) {
            log.debug("Cliente {} já foi posto na etapa {} do Nectar; nada a fazer",
                    clienteId, etapaId);
            return;
        }

        try {
            aplicar(cliente, oportunidadeId, fluxo, banco, funilId, etapaId);
        } catch (RuntimeException falha) {
            // A movimentação daqui não se desfaz por causa do CRM. A linha de ERRO é o que permite
            // descobrir depois, e é dela que o reprocessamento se alimenta.
            log.error("Falha ao mover a oportunidade {} do Nectar para a etapa {} ({})",
                    oportunidadeId, etapaId, fluxo, falha);
            registrar(clienteId, oportunidadeId, fluxo, banco, funilId, etapaId,
                    ResultadoSincronizacao.ERRO, mensagemDe(falha));
        }
    }

    private void aplicar(Cliente cliente, String oportunidadeId, EtapaDoFluxo fluxo, boolean banco,
            long funilId, long etapaId) {

        Map<String, Object> oportunidade = nectarClient.oportunidade(oportunidadeId);
        String etapaNome = nectarClient.nomeDaEtapa(funilId, etapaId);
        Long etapaAtual = NectarClient.etapaAtualDe(oportunidade);

        if (etapaAtual != null && etapaAtual == etapaId) {
            registrar(cliente.getId(), oportunidadeId, fluxo, banco, funilId, etapaId,
                    ResultadoSincronizacao.JA_NA_ETAPA, "O CRM já estava em " + etapaNome);
            return;
        }

        if (propriedades.somenteConferencia()) {
            // WARN a cada movimentação, e não DEBUG, pela mesma razão do modo conferência do
            // Gmail: ensaio esquecido ligado é a integração parecendo funcionar sem nunca mover
            // um cliente — e aqui quem percebe seria o comercial, não quem mexe no sistema.
            log.warn("Modo conferência: o cliente {} iria para \"{}\" no Nectar (oportunidade {}, "
                    + "etapa atual {}). Nada foi enviado.",
                    cliente.getNome(), etapaNome, oportunidadeId, etapaAtual);
            registrar(cliente.getId(), oportunidadeId, fluxo, banco, funilId, etapaId,
                    ResultadoSincronizacao.CONFERENCIA,
                    "Teria movido de %s para %s".formatted(etapaAtual, etapaNome));
            return;
        }

        nectarClient.moverParaEtapa(oportunidadeId, funilId, etapaId);
        log.info("Nectar: {} movido para \"{}\" (oportunidade {})",
                cliente.getNome(), etapaNome, oportunidadeId);
        registrar(cliente.getId(), oportunidadeId, fluxo, banco, funilId, etapaId,
                ResultadoSincronizacao.APLICADO, null);
    }

    /**
     * A camada barata de idempotência: se a última tentativa registrada para este cliente já o
     * levou a esta etapa, não há requisição a fazer.
     * <p>
     * Olha só a <b>última</b> linha, não todas: o cliente que voltou para uma etapa anterior
     * (projeto reprovado depois de aprovado) precisa ser movido de novo, e uma busca por "já
     * estivemos nesta etapa alguma vez" o deixaria preso na etapa mais recente para sempre.
     */
    private boolean jaAplicamos(Long clienteId, Long etapaId) {
        return sincronizacaoRepository
                .findFirstByClienteIdOrderByOcorridoEmDescIdDesc(clienteId)
                .filter(ultima -> ultima.getResultado().dispensaNovaChamada())
                .filter(ultima -> etapaId.equals(ultima.getEtapaId()))
                .isPresent();
    }

    private void registrar(Long clienteId, String oportunidadeId, EtapaDoFluxo fluxo, boolean banco,
            Long funilId, Long etapaId, ResultadoSincronizacao resultado, String detalhe) {

        sincronizacaoRepository.save(NectarEtapaSincronizacao.builder()
                .clienteId(clienteId)
                .oportunidadeId(oportunidadeId)
                .etapaFluxo(fluxo)
                .banco(banco)
                .funilId(funilId)
                .etapaId(etapaId)
                .etapaNome(etapaId == null ? null : nomeSeguro(funilId, etapaId))
                .resultado(resultado)
                .detalhe(truncar(detalhe))
                .ocorridoEm(Instant.now())
                .build());
    }

    /**
     * O nome da etapa é enfeite da trilha: se descobri-lo falhar (é uma chamada HTTP), gravar a
     * linha sem ele é melhor que perder o registro de uma falha — que é justamente o caso em que
     * esta chamada tem mais chance de também falhar.
     */
    private String nomeSeguro(Long funilId, Long etapaId) {
        if (funilId == null) {
            return null;
        }
        try {
            return nectarClient.nomeDaEtapa(funilId, etapaId);
        } catch (RuntimeException ignorada) {
            return null;
        }
    }

    private static String mensagemDe(RuntimeException falha) {
        return falha.getClass().getSimpleName() + ": " + falha.getMessage();
    }

    private static String truncar(String detalhe) {
        if (detalhe == null) {
            return null;
        }
        return detalhe.length() <= 1000 ? detalhe : detalhe.substring(0, 1000);
    }
}
