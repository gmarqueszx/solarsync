package com.conectsol.solarsync.common.referencia;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * As listas fechadas que o cadastro de cliente usa: municípios da Bahia e vendedores. Tem duas
 * funções, e a segunda é a que fez a classe existir:
 * <ol>
 *   <li>servir as listas ao frontend, que monta os campos de seleção do cadastro;</li>
 *   <li><b>normalizar o que vem de fora</b> para o valor canônico da lista. A importação do
 *       Nectar trazia "CACULE", "VITÓRIA DA CONQUISTA", "Vitória Da Conquista" e "Brumado" como
 *       cidades diferentes, e "Rodrigo soares"/"Deilson Abrantes" como vendedores fora da lista
 *       — quebrando o padrão que o cadastro pela tela respeita. Valor que não casa vira
 *       <b>nulo</b>, para a analista escolher na tela, em vez de texto livre entrando pela
 *       porta dos fundos.</li>
 * </ol>
 * <p>
 * ⚠️ <b>As listas viveram no frontend</b> (`src/data/constantes.ts`) até 17/09/2026, e é de lá
 * que estes arquivos foram gerados. Passaram para cá porque a importação precisa delas no
 * servidor: mantê-las só no frontend significaria duas cópias, e o CLAUDE.md (seção 6) já
 * registra o que acontece com regra de negócio duplicada entre os dois repositórios. O frontend
 * agora as lê de {@code GET /api/referencias}.
 * <p>
 * Arquivos de recurso, e não constantes em Java, porque são 417 municípios — e não tabela no
 * banco porque são dados de referência que mudam por deploy, não pela operação. Os vendedores
 * são o caso que mais tende a virar tabela: mudam a cada contratação.
 */
@Component
public class Referencias {

    private final List<String> municipios;
    private final List<String> vendedores;

    /** Chave normalizada → valor canônico. */
    private final Map<String, String> municipioPorChave;
    private final Map<String, String> vendedorPorChave;

    Referencias() {
        this.municipios = carregar("referencia/municipios-bahia.txt");
        this.vendedores = carregar("referencia/vendedores.txt");
        this.municipioPorChave = indexar(municipios);
        this.vendedorPorChave = indexar(vendedores);
    }

    public List<String> municipios() {
        return municipios;
    }

    public List<String> vendedores() {
        return vendedores;
    }

    /**
     * Devolve o nome canônico do município, ou {@code null} se não houver correspondência.
     * Compara sem acento, sem caixa e sem espaço sobrando — é o que faz "CACULE" virar "Caculé"
     * e "Vitória Da Conquista" virar "Vitória da Conquista".
     */
    public String municipioCanonico(String cidade) {
        return municipioPorChave.get(chave(cidade));
    }

    /**
     * Devolve o vendedor canônico, ou {@code null}. A lista tem só o primeiro nome (é o que o
     * cadastro pela tela oferece) e o CRM manda o nome completo, então casa pelo primeiro nome:
     * "Rodrigo soares" → "Rodrigo", "Deilson Abrantes" → "Deilson".
     * <p>
     * Nulo para quem não está na lista, e é o comportamento desejado: além dos vendedores, o
     * campo "responsável" do Nectar carrega gente do administrativo (a autora da oportunidade,
     * por exemplo). Adivinhar que essas pessoas são vendedoras poluiria o campo.
     */
    public String vendedorCanonico(String nomeCompleto) {
        String canonico = vendedorPorChave.get(chave(nomeCompleto));
        if (canonico != null) {
            return canonico;
        }
        String primeiroNome = primeiroNome(nomeCompleto);
        return primeiroNome == null ? null : vendedorPorChave.get(chave(primeiroNome));
    }

    private static String primeiroNome(String nomeCompleto) {
        if (nomeCompleto == null || nomeCompleto.isBlank()) {
            return null;
        }
        return nomeCompleto.trim().split("\\s+", 2)[0];
    }

    private static List<String> carregar(String recurso) {
        ClassPathResource arquivo = new ClassPathResource(recurso);
        try (var entrada = arquivo.getInputStream()) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(linha -> !linha.isEmpty())
                    .toList();
        } catch (IOException falha) {
            // Sem as listas o cadastro não tem o que oferecer e a importação não normaliza nada.
            // Falhar no boot é melhor que subir aceitando texto livre em silêncio.
            throw new UncheckedIOException("Não foi possível ler " + recurso, falha);
        }
    }

    private static Map<String, String> indexar(List<String> valores) {
        Map<String, String> indice = new LinkedHashMap<>();
        valores.forEach(valor -> indice.putIfAbsent(chave(valor), valor));
        return Map.copyOf(indice);
    }

    /** Sem acento, sem caixa, com os espaços internos colapsados. */
    private static String chave(String valor) {
        if (valor == null || valor.isBlank()) {
            return "";
        }
        return Normalizer.normalize(valor.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
