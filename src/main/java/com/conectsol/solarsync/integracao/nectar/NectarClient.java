package com.conectsol.solarsync.integracao.nectar;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Acesso HTTP ao Nectar. Fala com {@code GET /oportunidades} na API v1
 * ({@code https://app.nectarcrm.com.br/crm/api/1}), autenticando pelo cabeçalho
 * {@code Access-Token}.
 * <p>
 * Escrito sobre o {@link RestClient} do próprio Spring, sem SDK de terceiro: é uma chamada GET
 * paginada, e uma dependência a mais só traria mais superfície para conflitar com o Jackson 3 do
 * Boot 4.
 * <p>
 * ⚠️ <b>{@code pipeline=<nome do funil>} é o único filtro que a API respeita.</b> Foram testados
 * contra a API real {@code etapa}, {@code etapaAtual}, {@code idEtapa}, {@code sequencia},
 * {@code funilVenda}, {@code idFunilVenda}, {@code funil} e {@code etapaNome}: todos são
 * <b>silenciosamente ignorados</b> e devolvem a carteira inteira, o que é o pior modo de falhar —
 * um filtro que parece funcionar. Daí a consulta ser por funil e a etapa ser filtrada em Java.
 */
@Component
@ConditionalOnProperty(prefix = "solarsync.nectar", name = "ativo", havingValue = "true")
class NectarClient {

    private static final Logger log = LoggerFactory.getLogger(NectarClient.class);

    private static final ParameterizedTypeReference<List<OportunidadeNectar>> LISTA =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient http;
    private final NectarProperties propriedades;

    NectarClient(RestClient.Builder construtor, NectarProperties propriedades) {
        this.propriedades = propriedades;
        this.http = construtor
                .baseUrl(propriedades.baseUrl())
                .defaultHeader("Access-Token", propriedades.token() == null
                        ? ""
                        : propriedades.token())
                .build();
    }

    /**
     * As oportunidades que estão numa das etapas de entrada configuradas. Uma consulta paginada
     * por funil; dentro de cada funil, a etapa é filtrada aqui.
     */
    List<OportunidadeNectar> oportunidadesDeEntrada() {
        List<OportunidadeNectar> entradas = new ArrayList<>();

        for (String funil : propriedades.funisAConsultar()) {
            List<OportunidadeNectar> doFunil = percorrerFunil(funil);

            if (doFunil.isEmpty()) {
                // O nome do funil é digitado na configuração e tem de casar exatamente com o do
                // painel. Funil vazio quase sempre é nome errado, não carteira vazia — e sem
                // este aviso o sintoma seria nenhum cliente entrando, sem erro em lugar algum.
                log.warn("Funil '{}' não devolveu nenhuma oportunidade. Confira se o nome está "
                        + "exatamente como no painel do Nectar "
                        + "(solarsync.nectar.etapas-de-entrada).", funil);
                continue;
            }

            List<OportunidadeNectar> naEtapa = doFunil.stream()
                    .filter(propriedades::ehEtapaDeEntrada)
                    .toList();

            log.debug("Funil '{}': {} oportunidade(s), {} na(s) etapa(s) {}",
                    funil, doFunil.size(), naEtapa.size(), propriedades.etapasDoFunil(funil));

            entradas.addAll(naEtapa);
        }

        return entradas;
    }

    private List<OportunidadeNectar> percorrerFunil(String funil) {
        List<OportunidadeNectar> doFunil = new ArrayList<>();

        for (int pagina = 1; pagina <= propriedades.maximoPaginas(); pagina++) {
            List<OportunidadeNectar> desta = buscarPagina(funil, pagina);
            doFunil.addAll(desta);

            // Página menor que a pedida é o fim: a API não informa o total.
            if (desta.size() < propriedades.paginaTamanho()) {
                return doFunil;
            }
            if (pagina == propriedades.maximoPaginas()) {
                log.warn("Teto de {} páginas alcançado no funil '{}'. Oportunidade além disso não "
                        + "foi lida; ajuste solarsync.nectar.maximo-paginas.",
                        propriedades.maximoPaginas(), funil);
            }
        }

        return doFunil;
    }

    private List<OportunidadeNectar> buscarPagina(String funil, int pagina) {
        List<OportunidadeNectar> resposta = http.get()
                .uri(construtor -> construtor
                        .path("/oportunidades")
                        .queryParam("page", pagina)
                        .queryParam("displayLength", propriedades.paginaTamanho())
                        .queryParam("pipeline", funil)
                        .build())
                .retrieve()
                .body(LISTA);

        return resposta == null ? List.of() : resposta;
    }
}
