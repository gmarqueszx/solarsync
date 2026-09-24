package com.conectsol.solarsync.integracao.nectar;

import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração da sincronização com o Nectar (CRM). Nada tem valor no repositório: o token vem
 * do ambiente, como o segredo do JWT (item 6 do checklist).
 *
 * @param ativo              desligado por padrão. Ligado, cria o job agendado; desligado, o bean
 *                           do job nem existe — é o que mantém os testes e o dev local sem
 *                           chamada externa nenhuma
 * @param baseUrl            {@code https://app.nectarcrm.com.br/crm/api/1}, a raiz da API v1
 * @param token              gerado no Nectar em Configurações › Integrações › API, vinculado a um
 *                           usuário administrador. Enviado no cabeçalho {@code Access-Token}
 * @param etapasDeEntrada    <b>quais etapas de quais funis fazem o cliente entrar no fluxo</b>.
 *                           Ver {@link EtapaDeEntrada}
 * @param campoDataPagamento rótulo do campo personalizado que guarda a data do pagamento.
 *                           Configurável porque é rótulo digitado no painel do Nectar
 * @param intervalo          de quanto em quanto tempo puxar. Dez minutos porque o negócio
 *                           validado não tem urgência de segundos e a etapa seguinte é humana
 * @param paginaTamanho      o Nectar limita {@code displayLength} a 200
 * @param maximoPaginas      trava de segurança por funil: sem ela, um nome de funil que deixe de
 *                           existir faria o job varrer a carteira inteira a cada dez minutos
 */
@ConfigurationProperties("solarsync.nectar")
public record NectarProperties(
        boolean ativo,
        String baseUrl,
        String token,
        List<EtapaDeEntrada> etapasDeEntrada,
        String campoDataPagamento,
        Duration intervalo,
        int paginaTamanho,
        int maximoPaginas) {

    /**
     * Uma etapa de um funil. São <b>duas</b> informações e não uma porque o número da etapa é a
     * sequência dela <i>dentro</i> do funil — a etapa 4 existe em todos os treze funis da
     * ConectSol —, e porque o nome da etapa também se repete: "VALIDADO PELO FINANCEIRO" aparece
     * no funil "5- Financeiro" e, como "FUNCIONANDO | VALIDADO PELO FINANCEIRO", no funil
     * "7- Instalação". Filtrar por só um dos dois traria negócio de etapa errada.
     * <p>
     * O funil é também o filtro da consulta: {@code GET /oportunidades?pipeline=<nome do funil>}
     * é o <b>único</b> parâmetro de filtro que a API respeita — {@code etapa}, {@code etapaNome},
     * {@code funilVenda} e {@code idEtapa} foram todos testados e são silenciosamente ignorados,
     * devolvendo a carteira inteira. Por isso o funil precisa ser o nome exato e a etapa é
     * filtrada em Java.
     *
     * @param funil nome exato do funil, como aparece no painel ("5- Financeiro")
     * @param etapa nome da etapa dentro dele ("VALIDADO PELO FINANCEIRO")
     * @param banco a etapa identifica cliente de financiamento bancário. É <b>daqui</b> que sai a
     *              etiqueta "Banco" do cliente: a etapa de origem no CRM não sobrevive à
     *              importação (a oportunidade segue andando lá), então o que ela significa
     *              precisa virar um dado do cliente na hora em que ele entra. Só muda uma coisa,
     *              e fora do SolarSync — a etapa para onde o projeto aprovado volta no Nectar
     */
    public record EtapaDeEntrada(String funil, String etapa, boolean banco) {
    }

    /**
     * As duas etapas que o usuário confirmou em 17/09/2026 como a entrada do fluxo. Ficam como
     * padrão, e não só no {@code .env.example}, porque são o processo da ConectSol e não
     * preferência de ambiente — um deploy sem elas importaria da etapa errada em silêncio.
     */
    private static final List<EtapaDeEntrada> ETAPAS_PADRAO = List.of(
            new EtapaDeEntrada("5- Financeiro", "VALIDADO PELO FINANCEIRO", false),
            new EtapaDeEntrada("4- Nota Fiscal", "ADIANTAR PROJETO COELBA PARA BANCO OU VENDEDOR",
                    true));

    public NectarProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://app.nectarcrm.com.br/crm/api/1"
                : baseUrl.replaceAll("/+$", "");
        etapasDeEntrada = etapasDeEntrada == null || etapasDeEntrada.isEmpty()
                ? ETAPAS_PADRAO
                : etapasDeEntrada;
        campoDataPagamento = campoDataPagamento == null || campoDataPagamento.isBlank()
                ? "Data do Pagamento"
                : campoDataPagamento;
        intervalo = intervalo == null ? Duration.ofMinutes(10) : intervalo;
        paginaTamanho = paginaTamanho <= 0 || paginaTamanho > 200 ? 200 : paginaTamanho;
        maximoPaginas = maximoPaginas <= 0 ? 10 : maximoPaginas;
    }

    /**
     * A oportunidade está numa das etapas de entrada? Compara ignorando caixa, acento e espaço em
     * volta: os dois nomes são digitados por gente no painel do Nectar, e "Financeiro " com
     * espaço à direita não pode fazer o cliente deixar de entrar no fluxo — o custo desse erro é
     * um cliente que nunca aparece na fila da triagem, sem erro nenhum em lugar algum.
     */
    boolean ehEtapaDeEntrada(OportunidadeNectar oportunidade) {
        return entradaDe(oportunidade).isPresent();
    }

    /**
     * Qual etapa de entrada casou com esta oportunidade. Devolve a etapa, e não um booleano, porque
     * quem importa precisa de mais do que "entra ou não entra": precisa saber se aquela porta é a
     * dos clientes de banco.
     */
    Optional<EtapaDeEntrada> entradaDe(OportunidadeNectar oportunidade) {
        return etapasDeEntrada.stream()
                .filter(entrada -> mesmoTexto(entrada.funil(), oportunidade.nomeDoFunil())
                        && mesmoTexto(entrada.etapa(), oportunidade.etapaNome()))
                .findFirst();
    }

    /** Os funis a consultar, sem repetição — duas etapas do mesmo funil dão uma consulta só. */
    List<String> funisAConsultar() {
        return etapasDeEntrada.stream()
                .map(EtapaDeEntrada::funil)
                .filter(funil -> funil != null && !funil.isBlank())
                .distinct()
                .toList();
    }

    /** As etapas configuradas de um funil, para a mensagem de log dizer o que se esperava. */
    List<String> etapasDoFunil(String funil) {
        return etapasDeEntrada.stream()
                .filter(entrada -> mesmoTexto(entrada.funil(), funil))
                .map(EtapaDeEntrada::etapa)
                .toList();
    }

    private static boolean mesmoTexto(String esperado, String recebido) {
        if (esperado == null || recebido == null) {
            return false;
        }
        return normalizar(esperado).equals(normalizar(recebido));
    }

    private static String normalizar(String texto) {
        return Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
