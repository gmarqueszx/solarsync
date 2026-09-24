package com.conectsol.solarsync.integracao.nectar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.conectsol.solarsync.pendencia.StatusPendencia;
import com.conectsol.solarsync.projeto.StatusProjeto;
import com.conectsol.solarsync.vistoria.StatusVistoria;

/**
 * O mapa de etapas confirmado com a equipe em 22/09/2026, com os ids lidos de
 * {@code GET /pipelines} na API real. É este teste que acusa se alguém trocar um id de etapa sem
 * querer — o sintoma em produção seria o cliente aparecendo na etapa errada do CRM, que ninguém
 * de dentro do SolarSync veria.
 */
class EtapaDoFluxoTest {

    /** As propriedades como sobem sem configuração nenhuma, que é o caso de produção. */
    private static final NectarSaidaProperties PADRAO =
            new NectarSaidaProperties(true, false, 0, 0, null, null, null, 0);

    private static final long FUNIL_PROJETOS = 58570L;
    private static final long FUNIL_INSTALACAO = 58575L;

    @Test
    void oProjetoNormalSegueOMapaDoFunilDeProjetos() {
        assertThat(etapas(false)).containsExactlyInAnyOrderEntriesOf(Map.of(
                EtapaDoFluxo.PENDENCIA_ABERTA, 288714L,
                EtapaDoFluxo.PROJETO_PARA_FAZER, 288715L,
                EtapaDoFluxo.PROJETO_ENCAMINHADO, 288716L,
                EtapaDoFluxo.PROJETO_REPROVADO, 288717L,
                EtapaDoFluxo.PROJETO_REENCAMINHADO, 288718L,
                EtapaDoFluxo.PROJETO_APROVADO, 288719L,
                EtapaDoFluxo.VISTORIA_SOLICITADA, 288728L,
                EtapaDoFluxo.VISTORIA_REPROVADA, 288725L,
                EtapaDoFluxo.VISTORIA_RESOLICITADA, 288722L,
                EtapaDoFluxo.VISTORIA_APROVADA, 288726L));
    }

    /**
     * O fluxo Banco difere em <b>uma linha só</b>: o projeto aprovado vai para "PROJETO APROVADO
     * SEM PAGAMENTO (BANCO, NEGOCIAÇÃO, ETC)" em vez de "AGUARDANDO INSTALAÇÃO", porque ali o que
     * o comercial ainda acompanha é o financiamento.
     * <p>
     * Este teste existe para provar as outras dez: um mapa completo paralelo divergiria no dia em
     * que alguém mexesse só num deles.
     */
    @Test
    void oProjetoBancoDifereApenasNaEtapaDeAprovado() {
        Map<EtapaDoFluxo, Long> normal = etapas(false);
        Map<EtapaDoFluxo, Long> banco = etapas(true);

        assertThat(banco.get(EtapaDoFluxo.PROJETO_APROVADO)).isEqualTo(288686L);
        assertThat(normal.get(EtapaDoFluxo.PROJETO_APROVADO)).isEqualTo(288719L);

        for (EtapaDoFluxo fluxo : EtapaDoFluxo.values()) {
            if (fluxo != EtapaDoFluxo.PROJETO_APROVADO) {
                assertThat(banco.get(fluxo))
                        .as("a etapa %s deve ser a mesma nos dois fluxos", fluxo)
                        .isEqualTo(normal.get(fluxo));
            }
        }
    }

    /**
     * ⚠️ A etapa "aprovado após reencaminhamento" do requisito <b>não</b> tem entrada própria: o
     * projeto reencaminhado que é aprovado passa por {@code APROVADO}, e é a mesma etapa do CRM
     * nos dois casos. Uma entrada a mais só existiria para ser esquecida.
     */
    @Test
    void aprovadoDepoisDeReencaminhadoVaiParaAMesmaEtapa() {
        assertThat(EtapaDoFluxo.de(StatusProjeto.APROVADO))
                .isEqualTo(EtapaDoFluxo.PROJETO_APROVADO);
    }

    /**
     * Os dois status anteriores ao envio são situações diferentes aqui dentro e a mesma coisa no
     * CRM: "projeto para fazer". É a diferença de altitude entre os dois sistemas — o Nectar
     * acompanha a gestão do cliente, não a máquina de estados da homologação.
     */
    @Test
    void recebidoEAguardandoEnvioSaoAMesmaEtapaNoCrm() {
        assertThat(EtapaDoFluxo.de(StatusProjeto.RECEBIDO))
                .isEqualTo(EtapaDoFluxo.PROJETO_PARA_FAZER)
                .isEqualTo(EtapaDoFluxo.de(StatusProjeto.AGUARDANDO_ENVIO));
    }

    /**
     * A vistoria reaproveita o mesmo registro na reprova e na nova solicitação, então o status
     * novo sozinho não distingue a primeira da segunda — é o <b>anterior</b> que conta. Sem isso,
     * a correção de erro na obra apareceria no CRM como se fosse a primeira vistoria.
     */
    @Test
    void aVistoriaResolicitadaDepoisDeReprovaTemEtapaPropria() {
        assertThat(EtapaDoFluxo.de(null, StatusVistoria.SOLICITADA))
                .isEqualTo(EtapaDoFluxo.VISTORIA_SOLICITADA);
        assertThat(EtapaDoFluxo.de(StatusVistoria.REPROVADA, StatusVistoria.SOLICITADA))
                .isEqualTo(EtapaDoFluxo.VISTORIA_RESOLICITADA);
        assertThat(EtapaDoFluxo.de(StatusVistoria.SOLICITADA, StatusVistoria.APROVADA))
                .isEqualTo(EtapaDoFluxo.VISTORIA_APROVADA);
        assertThat(EtapaDoFluxo.de(StatusVistoria.SOLICITADA, StatusVistoria.REPROVADA))
                .isEqualTo(EtapaDoFluxo.VISTORIA_REPROVADA);
    }

    /**
     * Só a abertura da pendência move o cliente. Resolver não tem etapa própria porque quem o
     * move para "projeto para fazer" é o projeto que nasce logo em seguida — as duas
     * movimentações no mesmo instante do fluxo se atropelariam em ordem indeterminada.
     */
    @Test
    void apenasAPendenciaAbertaMoveOCliente() {
        assertThat(EtapaDoFluxo.de(StatusPendencia.ABERTA))
                .isEqualTo(EtapaDoFluxo.PENDENCIA_ABERTA);
        assertThat(EtapaDoFluxo.de(StatusPendencia.RESOLVIDA)).isNull();
        assertThat(EtapaDoFluxo.de(StatusPendencia.CANCELADA)).isNull();
    }

    /**
     * Toda situação tem etapa configurada nos dois fluxos. Um buraco aqui vira
     * {@code SEM_MAPEAMENTO} em produção — corretamente registrado, e ainda assim um cliente
     * parado na etapa errada do CRM.
     */
    @ParameterizedTest
    @EnumSource(EtapaDoFluxo.class)
    void todaSituacaoDoFluxoTemEtapaEFunil(EtapaDoFluxo fluxo) {
        assertThat(PADRAO.etapaId(fluxo, false)).as("fluxo normal").isNotNull();
        assertThat(PADRAO.etapaId(fluxo, true)).as("fluxo banco").isNotNull();

        long funilEsperado = fluxo.funil() == EtapaDoFluxo.Funil.PROJETOS
                ? FUNIL_PROJETOS
                : FUNIL_INSTALACAO;
        assertThat(PADRAO.funilId(fluxo)).isEqualTo(funilEsperado);
    }

    /**
     * O ensaio é o padrão quando ninguém configurou nada: mover a etapa é uma <b>escrita</b> no
     * CRM da empresa, e o corpo do PUT de oportunidade não é documentado. Estrear aplicando seria
     * descobrir o formato errado no CRM de produção.
     */
    @Test
    void oModoConferenciaEhOPadraoQuandoNaoConfigurado() {
        NectarSaidaProperties semConfiguracao =
                new NectarSaidaProperties(true, null, 0, 0, null, null, null, 0);
        assertThat(semConfiguracao.somenteConferencia()).isTrue();
    }

    private static Map<EtapaDoFluxo, Long> etapas(boolean banco) {
        return PADRAO.etapasEfetivas(banco);
    }
}
