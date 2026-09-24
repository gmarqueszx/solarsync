package com.conectsol.solarsync.integracao.nectar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
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
 * Faz as duas pontas: lê as oportunidades das etapas de entrada e move a oportunidade de etapa
 * quando o projeto anda aqui dentro. Um cliente HTTP só porque são o mesmo host, o mesmo token e o
 * mesmo cabeçalho — dois beans exigiriam duas configurações que nunca poderiam divergir.
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

    // ---------------------------------------------------------------------------------------
    // Saída: mover a oportunidade de etapa.
    //
    // ⚠️ O corpo do PUT de oportunidade NÃO é documentado, como nenhum outro campo desta API. O
    // que se sabe veio de OPTIONS /oportunidades/{id}, que responde "allow: HEAD,DELETE,GET,
    // OPTIONS,PUT". Daí as duas decisões defensivas abaixo, e daí o modo conferência ser o padrão.
    // ---------------------------------------------------------------------------------------

    private static final ParameterizedTypeReference<Map<String, Object>> MAPA =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LISTA_DE_MAPAS =
            new ParameterizedTypeReference<>() {
            };

    /**
     * Os funis com suas etapas ({@code GET /pipelines}), memorizados.
     * <p>
     * Memorizados porque mudam por configuração no painel, não pela operação — e porque a
     * alternativa seria uma consulta de 45 KB a cada movimentação de cliente. Quando a etapa
     * procurada não está no que foi memorizado, a lista é relida uma vez: é o que faz uma etapa
     * criada hoje no painel funcionar sem reiniciar a aplicação.
     */
    private final AtomicReference<List<Map<String, Object>>> funisMemorizados =
            new AtomicReference<>();

    /** A oportunidade inteira, como mapa cru. */
    Map<String, Object> oportunidade(String oportunidadeId) {
        return http.get()
                .uri("/oportunidades/{id}", oportunidadeId)
                .retrieve()
                .body(MAPA);
    }

    /**
     * O id da etapa em que a oportunidade está agora, ou {@code null} se a resposta não disser.
     * <p>
     * Lê {@code etapaAtual.id}, e não {@code etapa}: {@code etapa} é a <b>sequência</b> da etapa
     * dentro do funil (a etapa 4 existe nos treze funis da ConectSol), então compará-la com um id
     * configurado acertaria por acaso ou erraria em silêncio — a mesma armadilha que a integração
     * de entrada já documenta.
     */
    static Long etapaAtualDe(Map<String, Object> oportunidade) {
        Object etapaAtual = oportunidade == null ? null : oportunidade.get("etapaAtual");
        if (!(etapaAtual instanceof Map<?, ?> mapa) || !(mapa.get("id") instanceof Number id)) {
            return null;
        }
        return id.longValue();
    }

    /**
     * Move a oportunidade para uma etapa de um funil.
     * <p>
     * <b>Lê, altera e devolve a oportunidade inteira</b> em vez de mandar um corpo mínimo. Num
     * {@code PUT} de uma API que não documenta o contrato, mandar só os campos que interessam é
     * apostar que o servidor faz merge — e se ele substituir, o negócio perde valor, responsável e
     * campos personalizados de uma vez. Reenviar o que veio é a aposta que, no pior caso, não
     * destrói nada.
     * <p>
     * Os objetos de funil e etapa também não são montados à mão: são <b>copiados de
     * {@code /pipelines}</b>, com todos os campos que o Nectar põe neles. Montar
     * {@code {"id": 288716}} e esperar que baste seria a mesma aposta, um nível abaixo.
     */
    void moverParaEtapa(String oportunidadeId, long funilId, long etapaId) {
        Map<String, Object> funil = funilComEtapa(funilId, etapaId);
        Map<String, Object> etapa = etapaDoFunil(funil, etapaId);

        Map<String, Object> corpo = new LinkedHashMap<>(oportunidade(oportunidadeId));
        corpo.put("funilVenda", semAsEtapas(funil));
        corpo.put("pipeline", funil.get("nome"));
        corpo.put("etapaAtual", etapa);
        // Mantido coerente com a etapa nova: é a sequência dela dentro do funil, e deixá-la com o
        // valor antigo daria uma oportunidade que se contradiz.
        corpo.put("etapa", etapa.get("sequencia"));
        corpo.put("etapaNome", etapa.get("nome"));

        http.put()
                .uri("/oportunidades/{id}", oportunidadeId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(corpo)
                .retrieve()
                .toBodilessEntity();
    }

    /** O nome da etapa, para a trilha de auditoria dizer para onde o cliente foi (ou iria). */
    String nomeDaEtapa(long funilId, long etapaId) {
        return String.valueOf(etapaDoFunil(funilComEtapa(funilId, etapaId), etapaId).get("nome"));
    }

    private Map<String, Object> funilComEtapa(long funilId, long etapaId) {
        Map<String, Object> funil = procurarFunil(funis(false), funilId, etapaId);
        if (funil != null) {
            return funil;
        }
        // Etapa criada no painel depois da última leitura: relê uma vez antes de desistir.
        funil = procurarFunil(funis(true), funilId, etapaId);
        if (funil == null) {
            throw new IllegalStateException(
                    "Etapa %d não existe no funil %d do Nectar. Confira solarsync.nectar.saida."
                            .formatted(etapaId, funilId));
        }
        return funil;
    }

    private static Map<String, Object> procurarFunil(List<Map<String, Object>> funis, long funilId,
            long etapaId) {
        return funis.stream()
                .filter(funil -> funil.get("id") instanceof Number id && id.longValue() == funilId)
                .filter(funil -> etapaDoFunilOuNulo(funil, etapaId) != null)
                .findFirst()
                .orElse(null);
    }

    private static Map<String, Object> etapaDoFunil(Map<String, Object> funil, long etapaId) {
        Map<String, Object> etapa = etapaDoFunilOuNulo(funil, etapaId);
        if (etapa == null) {
            throw new IllegalStateException("Etapa %d não encontrada no funil".formatted(etapaId));
        }
        return etapa;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> etapaDoFunilOuNulo(Map<String, Object> funil, long etapaId) {
        Object sequencias = funil.get("sequencias");
        if (!(sequencias instanceof List<?> lista)) {
            return null;
        }
        return lista.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .filter(etapa -> etapa.get("id") instanceof Number id && id.longValue() == etapaId)
                .findFirst()
                .orElse(null);
    }

    /**
     * O funil sem a lista de etapas. É o formato em que ele aparece <i>dentro</i> de uma
     * oportunidade ({@code {id, nome, tipo, cascataCicloVida}}); devolver as onze etapas junto
     * seria mandar de volta um objeto que a resposta original nunca teve.
     */
    private static Map<String, Object> semAsEtapas(Map<String, Object> funil) {
        Map<String, Object> copia = new LinkedHashMap<>(funil);
        copia.remove("sequencias");
        return copia;
    }

    private List<Map<String, Object>> funis(boolean forcarLeitura) {
        List<Map<String, Object>> memorizados = funisMemorizados.get();
        if (memorizados != null && !forcarLeitura) {
            return memorizados;
        }
        List<Map<String, Object>> lidos = http.get()
                .uri("/pipelines")
                .retrieve()
                .body(LISTA_DE_MAPAS);
        List<Map<String, Object>> resultado = lidos == null ? List.of() : lidos;
        funisMemorizados.set(resultado);
        return resultado;
    }
}
