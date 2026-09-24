package com.conectsol.solarsync.integracao.nectar;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A volta do caminho: a etapa do Nectar passa a seguir o status do projeto no SolarSync.
 * <p>
 * Os ids abaixo foram <b>conferidos contra a API real</b> em 22/09/2026, por
 * {@code GET /pipelines}, que devolve todos os funis com suas etapas — é o endpoint que faltava
 * quando a integração de entrada foi escrita e que tornou o filtro por nome de funil necessário
 * naquela ponta. Ficam como padrão, e não só no {@code .env.example}, pela mesma razão das etapas
 * de entrada: são o processo da ConectSol, não preferência de ambiente. Um deploy sem eles moveria
 * cliente para etapa nenhuma, em silêncio.
 *
 * @param ativo                 desligado por padrão, como as duas integrações de entrada. Ligado,
 *                              cria o listener e o job de reprocessamento; desligado, nenhum dos
 *                              dois beans existe — e nada é enviado ao CRM nem nos testes
 * @param somenteConferencia    <b>ligado por padrão</b>, ao contrário do {@code ativo}. É o mesmo
 *                              ensaio da leitura do Gmail, e aqui pesa ainda mais: mover a etapa é
 *                              uma <b>escrita</b> no CRM da empresa, e o corpo do {@code PUT} de
 *                              oportunidade não é documentado. Em conferência o job faz tudo —
 *                              resolve o cliente, a etapa, consulta o CRM — e grava em
 *                              {@code nectar_etapa_sincronizacao} o que teria feito, sem o
 *                              {@code PUT}. Conferida a trilha, desligue
 * @param funilProjetosId       "6- Projetos" (58570), onde o cliente fica até a homologação sair
 * @param funilInstalacaoId     "7- Instalação" (58575), de onde a vistoria é acompanhada
 * @param etapas                a etapa do Nectar de cada situação do fluxo
 * @param etapasBanco           as <b>sobreposições</b> do fluxo Banco. Só o que difere entra aqui,
 *                              e hoje é uma linha só: o projeto aprovado de Banco vai para
 *                              "PROJETO APROVADO SEM PAGAMENTO", não para "AGUARDANDO INSTALAÇÃO".
 *                              Mapa de diferenças em vez de uma segunda tabela completa porque
 *                              duas tabelas iguais em dez das onze linhas divergem no dia em que
 *                              alguém mexe só numa
 * @param intervaloReprocessamento de quanto em quanto tempo tentar de novo as falhas
 * @param maximoPorReprocessamento teto de falhas retomadas por execução, para um CRM fora do ar por
 *                              um dia não gerar uma rajada de chamadas quando voltar
 */
@ConfigurationProperties("solarsync.nectar.saida")
public record NectarSaidaProperties(
        boolean ativo,
        Boolean somenteConferencia,
        long funilProjetosId,
        long funilInstalacaoId,
        Map<EtapaDoFluxo, Long> etapas,
        Map<EtapaDoFluxo, Long> etapasBanco,
        Duration intervaloReprocessamento,
        int maximoPorReprocessamento) {

    private static final long FUNIL_PROJETOS = 58570L;
    private static final long FUNIL_INSTALACAO = 58575L;

    /**
     * O mapeamento confirmado pela equipe em 22/09/2026, com os ids de {@code GET /pipelines}.
     * <pre>
     * 6- Projetos (58570)
     *   288714  PENDÊNCIA CONTA COELBA OU ALTERAÇÃO NO PADRÃO (VER OBSERVAÇÃO)
     *   288715  PROJETO PARA FAZER
     *   288716  PROJETO ENCAMINHADO
     *   288717  PROJETO REPROVADO
     *   288718  PROJETO RETIFICADO E ENCAMINHADO NOVAMENTE
     *   288719  PROJETO APROVADO - AGUARDANDO INSTALAÇÃO
     *   288686  PROJETO APROVADO SEM PAGAMENTO (BANCO, NEGOCIAÇÃO, ETC)   [só Banco]
     * 7- Instalação (58575)
     *   288728  VISTORIA SOLICITADA
     *   288725  VISTORIA REPROVADA
     *   288722  CORREÇÃO DE ERRO NA OBRA E VISTORIA SOLICITADA NOVAMENTE
     *   288726  VISTORIA APROVADA - 100% CONCLUIDO - FALTA LIGAR O INVERSOR
     * </pre>
     * ⚠️ O funil 7 tem <b>duas</b> etapas de vistoria aprovada. A escolhida é a 288726, por decisão
     * do usuário em 22/09/2026 — a 288720 ("APROVADA - PENDENTE INSTALAÇÃO") ficou de fora.
     */
    private static final Map<EtapaDoFluxo, Long> ETAPAS_PADRAO = Map.of(
            EtapaDoFluxo.PENDENCIA_ABERTA, 288714L,
            EtapaDoFluxo.PROJETO_PARA_FAZER, 288715L,
            EtapaDoFluxo.PROJETO_ENCAMINHADO, 288716L,
            EtapaDoFluxo.PROJETO_REPROVADO, 288717L,
            EtapaDoFluxo.PROJETO_REENCAMINHADO, 288718L,
            EtapaDoFluxo.PROJETO_APROVADO, 288719L,
            EtapaDoFluxo.VISTORIA_SOLICITADA, 288728L,
            EtapaDoFluxo.VISTORIA_REPROVADA, 288725L,
            EtapaDoFluxo.VISTORIA_RESOLICITADA, 288722L,
            EtapaDoFluxo.VISTORIA_APROVADA, 288726L);

    private static final Map<EtapaDoFluxo, Long> ETAPAS_BANCO_PADRAO = Map.of(
            EtapaDoFluxo.PROJETO_APROVADO, 288686L);

    public NectarSaidaProperties {
        // Boolean e não boolean: só assim dá para distinguir "não configurado" (que vira o padrão
        // seguro, ligado) de "configurado como falso" — com o primitivo, ausência e false são a
        // mesma coisa e o ensaio nunca seria o padrão.
        somenteConferencia = somenteConferencia == null || somenteConferencia;
        funilProjetosId = funilProjetosId <= 0 ? FUNIL_PROJETOS : funilProjetosId;
        funilInstalacaoId = funilInstalacaoId <= 0 ? FUNIL_INSTALACAO : funilInstalacaoId;
        etapas = etapas == null || etapas.isEmpty() ? ETAPAS_PADRAO : etapas;
        etapasBanco = etapasBanco == null || etapasBanco.isEmpty()
                ? ETAPAS_BANCO_PADRAO
                : etapasBanco;
        intervaloReprocessamento = intervaloReprocessamento == null
                ? Duration.ofMinutes(30)
                : intervaloReprocessamento;
        maximoPorReprocessamento = maximoPorReprocessamento <= 0 ? 50 : maximoPorReprocessamento;
    }

    /**
     * A etapa do Nectar para uma situação do fluxo. O mapa de Banco é consultado primeiro e cai no
     * geral quando não tem entrada própria — que é o caso de dez das onze situações.
     *
     * @return {@code null} quando a situação não tem etapa configurada; o chamador registra
     *         {@code SEM_MAPEAMENTO} em vez de inventar um destino
     */
    Long etapaId(EtapaDoFluxo fluxo, boolean banco) {
        if (banco) {
            Long doBanco = etapasBanco.get(fluxo);
            if (doBanco != null) {
                return doBanco;
            }
        }
        return etapas.get(fluxo);
    }

    long funilId(EtapaDoFluxo fluxo) {
        return fluxo.funil() == EtapaDoFluxo.Funil.PROJETOS ? funilProjetosId : funilInstalacaoId;
    }

    /** Cópia defensiva com ordem de enum, para o log da configuração sair legível. */
    Map<EtapaDoFluxo, Long> etapasEfetivas(boolean banco) {
        Map<EtapaDoFluxo, Long> efetivas = new EnumMap<>(EtapaDoFluxo.class);
        for (EtapaDoFluxo fluxo : EtapaDoFluxo.values()) {
            Long id = etapaId(fluxo, banco);
            if (id != null) {
                efetivas.put(fluxo, id);
            }
        }
        return efetivas;
    }
}
