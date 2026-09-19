package com.conectsol.solarsync.integracao.gmail;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * Lê o texto do e-mail do Portal da Geração Distribuída da Neoenergia e diz o que ele afirma.
 * Não conhece Gmail nem banco: recebe texto e devolve {@link RetornoCoelba}, o que permite
 * testá-lo com e-mail colado à mão — e é assim que os e-mails reais viraram teste.
 *
 * <h2>Dois caminhos, nesta ordem</h2>
 * <ol>
 *   <li><b>A linha {@code Etapa atual:}</b>, quando existe. O e-mail real é uma notificação de
 *       mudança de etapa, e o resultado está no nome da etapa nova — não em adjetivo nenhum. O
 *       parágrafo em prosa é <b>idêntico</b> no e-mail que confirma o envio e no que anuncia a
 *       aprovação, então ler o corpo livre ali é ler o texto que não diz nada. Etapa
 *       desconhecida vira não reconhecido, e <b>não</b> volta para o caminho 2: num e-mail
 *       estruturado, cair na busca por termo é exatamente como se erra.</li>
 *   <li><b>Busca por termos no texto</b>, só quando não há {@code Etapa atual}. É o caso do
 *       cancelamento, que não anuncia etapa — anuncia "Sua solicitação ... foi cancelada".</li>
 * </ol>
 *
 * <b>O princípio é não adivinhar.</b> O status do projeto muda sozinho, e vai para o
 * {@code historico_status} que sustenta todas as métricas do dashboard — então um palpite errado
 * custa mais que uma leitura recusada. Em concreto:
 * <ul>
 *   <li>afirmação dupla no mesmo e-mail (aprovação <i>e</i> reprovação) não escolhe uma: devolve
 *       ambíguo, e o projeto fica como está;</li>
 *   <li>negação é tratada: "não deferida" é reprovação, não aprovação. Sem isto, a negação de um
 *       termo de aprovação seria lida como aprovação — o erro mais caro possível aqui;</li>
 *   <li>o número da solicitação não é validado por formato, e sim pelo casamento com um projeto
 *       existente (em {@code RetornoCoelbaService}). Por isso a extração é generosa: todos os
 *       números do texto são candidatos, com preferência pelos que estão perto de uma palavra
 *       como "solicitação" ou "protocolo". Errar o formato do número da Coelba faria a
 *       integração perder e-mail; ser generoso aqui só produz candidato que não casa com nada.
 *       </li>
 * </ul>
 */
@Component
public class ParserEmailCoelba {

    /**
     * Números <b>perto</b> de uma palavra que anuncia protocolo. Primeira tentativa de extração,
     * e a de maior confiança.
     */
    private static final Pattern NUMERO_ROTULADO = Pattern.compile(
            "(?:solicitacao|protocolo|processo|numero|n[o°º]\\.?)"
                    + "[^0-9\\n]{0,25}"
                    + "([0-9][0-9.\\-/ ]{2,25}[0-9])");

    /** Segunda tentativa: qualquer número. Só entra em cena se a primeira não achou nada. */
    private static final Pattern QUALQUER_NUMERO = Pattern.compile("[0-9][0-9.\\-/]*[0-9]|[0-9]");

    /**
     * Palavras que negam o termo que vem depois. Procuradas na janela imediatamente anterior à
     * ocorrência: "nao foi deferida", "sem aprovacao".
     */
    private static final Pattern NEGACAO = Pattern.compile("\\b(?:nao|sem|nenhum[ao]?)\\b");

    /** Quantos caracteres antes do termo são olhados em busca de negação. */
    private static final int JANELA_DE_NEGACAO = 25;

    /** Quanto texto a partir da reprovação vira o motivo gravado no projeto. */
    private static final int TAMANHO_DO_MOTIVO = 900;

    private final CoelbaProperties propriedades;
    private final List<Pattern> padroesAprovado;
    private final List<Pattern> padroesReprovado;
    private final List<Pattern> padroesEmAnalise;
    private final Pattern etapaAtual;
    private final Pattern linhaDoMotivo;

    public ParserEmailCoelba(CoelbaProperties propriedades) {
        this.propriedades = propriedades;
        this.padroesAprovado = compilar(propriedades.termosAprovado());
        this.padroesReprovado = compilar(propriedades.termosReprovado());
        this.padroesEmAnalise = compilar(propriedades.termosEmAnalise());
        this.etapaAtual = rotulo(propriedades.rotuloEtapaAtual());
        this.linhaDoMotivo = rotulo(propriedades.rotuloMotivo());
    }

    /**
     * "Rótulo: valor até o fim da linha". Sem exigir começo de linha, porque a conversão de HTML
     * para texto nem sempre preserva a quebra original — e um rótulo que só casa no começo da
     * linha falharia em silêncio, que é o modo de falhar que não se quer aqui.
     */
    private static Pattern rotulo(String texto) {
        return Pattern.compile(
                Pattern.quote(normalizarPreservandoIndices(texto)) + "\\s*:\\s*([^\\n]{1,300})");
    }

    /**
     * Cada termo vira um padrão com fronteira de palavra <b>no começo</b> e sufixo livre no fim.
     * As duas metades importam:
     * <ul>
     *   <li>sufixo livre é o que permite configurar o radical: {@code deferid} casa "deferida" e
     *       "deferido" sem precisar das duas variantes;</li>
     *   <li>a fronteira no começo é o que impede {@code deferid} de casar <b>dentro</b> de
     *       "indeferido". Sem ela, todo indeferimento teria os dois grupos presentes e cairia
     *       como ambíguo — ou seja, a integração nunca aplicaria uma reprovação anunciada como
     *       "indeferida", que é a redação mais provável da Coelba.</li>
     * </ul>
     */
    private static List<Pattern> compilar(List<String> termos) {
        return termos.stream()
                .map(termo -> Pattern.compile(
                        "\\b" + Pattern.quote(normalizarPreservandoIndices(termo))))
                .toList();
    }

    public RetornoCoelba ler(String texto) {
        if (texto == null || texto.isBlank()) {
            return RetornoCoelba.naoReconhecido(List.of());
        }

        // Normalizado preservando os índices: cada caractere do original corresponde a um do
        // normalizado, então a posição de um termo aqui vale como posição no texto original — é
        // o que permite recortar o motivo da reprova com os acentos intactos.
        String normalizado = normalizarPreservandoIndices(texto);
        List<String> numeros = extrairNumeros(normalizado);

        Matcher etapa = etapaAtual.matcher(normalizado);
        if (etapa.find()) {
            return porEtapa(etapa.group(1).trim(), texto, normalizado, numeros);
        }

        return porTermos(texto, normalizado, numeros);
    }

    /**
     * O caminho do e-mail estruturado. A etapa nova é o resultado, e nada mais do texto é
     * consultado — nem como desempate, nem como reforço.
     */
    private RetornoCoelba porEtapa(String etapa, String texto, String normalizado,
            List<String> numeros) {
        boolean aprovado = contemAlgum(etapa, propriedades.etapasAprovado());
        boolean reprovado = contemAlgum(etapa, propriedades.etapasReprovado());
        boolean vistoriaSolicitada = contemAlgum(etapa, propriedades.etapasVistoriaSolicitada());
        boolean vistoriaAprovada = contemAlgum(etapa, propriedades.etapasVistoriaAprovada());
        boolean emAnalise = contemAlgum(etapa, propriedades.etapasEmAnalise());

        // Uma etapa só, mas as listas são configuradas por gente: duas casando com o mesmo valor
        // é erro de configuração, e recusar é melhor que deixar a ordem do código decidir em
        // silêncio qual delas vale.
        long quantasCasaram = Stream
                .of(aprovado, reprovado, vistoriaSolicitada, vistoriaAprovada, emAnalise)
                .filter(Boolean::booleanValue)
                .count();
        if (quantasCasaram > 1) {
            return RetornoCoelba.ambiguo(numeros);
        }

        if (aprovado) {
            return RetornoCoelba.de(ResultadoCoelba.APROVADO, numeros, null);
        }
        if (reprovado) {
            return RetornoCoelba.de(ResultadoCoelba.REPROVADO, numeros,
                    motivoDaLinha(texto, normalizado));
        }
        if (vistoriaSolicitada) {
            return RetornoCoelba.de(ResultadoCoelba.VISTORIA_SOLICITADA, numeros, null);
        }
        if (vistoriaAprovada) {
            return RetornoCoelba.de(ResultadoCoelba.VISTORIA_APROVADA, numeros, null);
        }
        if (emAnalise) {
            return RetornoCoelba.de(ResultadoCoelba.EM_ANALISE, numeros, null);
        }

        // Etapa que nenhuma lista conhece. Não reconhecido é a resposta certa: a Neoenergia
        // acrescentou uma etapa, ou renomeou uma, e quem decide o que ela significa é uma
        // pessoa — acrescentando o valor a solarsync.coelba.etapas-*, sem deploy.
        return RetornoCoelba.naoReconhecido(numeros);
    }

    private static boolean contemAlgum(String etapa, List<String> valores) {
        return valores.stream()
                .map(ParserEmailCoelba::normalizarPreservandoIndices)
                .anyMatch(etapa::contains);
    }

    /** O caminho do e-mail em prosa, sem linha de etapa — o cancelamento é o caso real. */
    private RetornoCoelba porTermos(String texto, String normalizado, List<String> numeros) {
        Ocorrencias aprovado = procurar(normalizado, padroesAprovado);
        Ocorrencias reprovado = procurar(normalizado, padroesReprovado);

        // "não deferida" afirma reprovação. Herdar a negação da aprovação é o que impede o pior
        // erro possível: ler a negação de um termo de aprovação como aprovação.
        boolean afirmaAprovacao = aprovado.temAfirmacao();
        boolean afirmaReprovacao = reprovado.temAfirmacao() || aprovado.temNegacao();

        if (afirmaAprovacao && afirmaReprovacao) {
            return RetornoCoelba.ambiguo(numeros);
        }
        if (afirmaAprovacao) {
            return RetornoCoelba.de(ResultadoCoelba.APROVADO, numeros, null);
        }
        if (afirmaReprovacao) {
            int inicio = reprovado.temAfirmacao()
                    ? reprovado.primeiraAfirmacao()
                    : aprovado.primeiraNegacao();
            // A linha rotulada, quando existe, é melhor motivo que o recorte em volta do termo:
            // no cancelamento real o termo aparece na saudação ("...foi cancelada"), e o recorte
            // traria o cabeçalho e o link de acompanhamento junto. O que a analista precisa ler
            // é "Tensão Informada (127V) diverge da cadastrada".
            String rotulado = motivoDaLinha(texto, normalizado);
            return RetornoCoelba.de(ResultadoCoelba.REPROVADO, numeros,
                    rotulado != null ? rotulado : motivo(texto, inicio));
        }
        if (procurar(normalizado, padroesEmAnalise).temAfirmacao()) {
            return RetornoCoelba.de(ResultadoCoelba.EM_ANALISE, numeros, null);
        }

        return RetornoCoelba.naoReconhecido(numeros);
    }

    /** Onde cada grupo de termos apareceu, separando afirmação de ocorrência negada. */
    private record Ocorrencias(List<Integer> afirmacoes, List<Integer> negacoes) {

        boolean temAfirmacao() {
            return !afirmacoes.isEmpty();
        }

        boolean temNegacao() {
            return !negacoes.isEmpty();
        }

        int primeiraAfirmacao() {
            return afirmacoes.get(0);
        }

        int primeiraNegacao() {
            return negacoes.get(0);
        }
    }

    private static Ocorrencias procurar(String normalizado, List<Pattern> padroes) {
        List<Integer> afirmacoes = new ArrayList<>();
        List<Integer> negacoes = new ArrayList<>();

        for (Pattern padrao : padroes) {
            Matcher casador = padrao.matcher(normalizado);
            while (casador.find()) {
                if (negado(normalizado, casador.start())) {
                    negacoes.add(casador.start());
                } else {
                    afirmacoes.add(casador.start());
                }
            }
        }

        afirmacoes.sort(null);
        negacoes.sort(null);
        return new Ocorrencias(afirmacoes, negacoes);
    }

    private static boolean negado(String normalizado, int posicaoDoTermo) {
        int inicio = Math.max(0, posicaoDoTermo - JANELA_DE_NEGACAO);
        return NEGACAO.matcher(normalizado.substring(inicio, posicaoDoTermo)).find();
    }

    /**
     * Candidatos a número de solicitação, sem pontuação e sem repetição, na ordem de aparição.
     * Primeiro os rotulados; só se não houver nenhum é que qualquer número serve.
     */
    private List<String> extrairNumeros(String normalizado) {
        Set<String> rotulados = casar(NUMERO_ROTULADO, normalizado, 1);
        Set<String> escolhidos = rotulados.isEmpty()
                ? casar(QUALQUER_NUMERO, normalizado, 0)
                : rotulados;

        return escolhidos.stream().limit(propriedades.maximoCandidatos()).toList();
    }

    private Set<String> casar(Pattern padrao, String texto, int grupo) {
        Set<String> encontrados = new LinkedHashSet<>();
        Matcher casador = padrao.matcher(texto);
        while (casador.find()) {
            String digitos = casador.group(grupo).replaceAll("\\D", "");
            if (digitos.length() >= propriedades.minimoDigitos()) {
                encontrados.add(digitos);
            }
        }
        return encontrados;
    }

    /**
     * O motivo da linha rotulada ("Motivo do cancelamento: ..."), com os acentos intactos.
     * <p>
     * A busca é feita no texto normalizado e o recorte no original, aproveitando que os índices
     * são os mesmos — é a mesma razão pela qual {@link #normalizarPreservandoIndices} existe.
     * Devolve nulo quando não há linha rotulada, e aí quem chama decide o que fazer.
     */
    private String motivoDaLinha(String textoOriginal, String normalizado) {
        Matcher casador = linhaDoMotivo.matcher(normalizado);
        if (!casador.find()) {
            return null;
        }
        String recorte = textoOriginal.substring(casador.start(1), casador.end(1)).trim();
        return recorte.isBlank() ? null : recorte;
    }

    /**
     * Recorta o motivo a partir do começo da linha onde a reprovação aparece — a frase costuma
     * começar antes do termo ("o projeto foi reprovado por falta de ART"), então cortar no termo
     * perderia o começo dela.
     */
    private static String motivo(String textoOriginal, int posicaoDoTermo) {
        int inicioDaLinha = textoOriginal.lastIndexOf('\n', posicaoDoTermo) + 1;
        int fim = Math.min(textoOriginal.length(), inicioDaLinha + TAMANHO_DO_MOTIVO);
        String recorte = textoOriginal.substring(inicioDaLinha, fim).trim();
        return recorte.isBlank() ? null : recorte;
    }

    /**
     * Minúsculas e sem acento, <b>com o mesmo número de caracteres do original</b>. Um
     * {@code Normalizer.normalize} do texto inteiro decomporia "ã" em dois caracteres e
     * desalinharia os índices, e é dos índices que sai o recorte do motivo da reprova. Por isso a
     * decomposição é feita caractere a caractere, aproveitando que a primeira posição da forma
     * decomposta é sempre a letra base.
     */
    static String normalizarPreservandoIndices(String texto) {
        StringBuilder resultado = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char caractere = texto.charAt(i);
            String decomposto = Normalizer.normalize(String.valueOf(caractere), Normalizer.Form.NFD);
            char base = decomposto.isEmpty() ? caractere : decomposto.charAt(0);
            resultado.append(Character.toLowerCase(base));
        }
        // Sem um toLowerCase da string inteira no fim, de propósito: há caractere cuja minúscula
        // tem mais de um caractere, e isso desalinharia os índices que o recorte do motivo usa.
        return resultado.toString();
    }
}
