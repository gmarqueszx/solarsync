package com.conectsol.solarsync.integracao.nectar;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Uma oportunidade (negócio) como o Nectar a devolve em {@code GET /oportunidades}.
 * <p>
 * <b>É o único ponto do projeto que conhece o formato do Nectar</b> — o resto do módulo fala em
 * termos do domínio. Se o CRM renomear um campo, é aqui que se conserta.
 * <p>
 * Os nomes dos componentes foram conferidos contra a resposta real da API da ConectSol em
 * 17/09/2026 (os campos de uma oportunidade não são documentados).
 * <p>
 * A resposta traz dezenas de campos que não interessam (autor, valores, produtos, propostas...).
 * O {@code @JsonIgnoreProperties} é o que os descarta, e está no DTO em vez de depender da
 * configuração do Jackson do Boot porque o {@code RestClient} das integrações é construído à mão
 * (ver {@code IntegracaoConfig}) e não herda essa configuração.
 * <p>
 * Três armadilhas que a conferência revelou, e que teriam quebrado ou sujado a importação:
 * <ul>
 *   <li>{@code id} é <b>número</b>, não texto, e {@code etapa} é o <b>número da sequência</b> da
 *       etapa no funil — não um objeto. O nome da etapa vem em {@code etapaNome};</li>
 *   <li><b>{@code dataCriacao} vem corrompida</b> numa boa parte dos registros: aparecem anos
 *       como 0024, 0026 e 0028 (dia e mês trocados na origem, provavelmente numa importação
 *       antiga). É inútil, e por isso não é lida aqui. {@code stageEntryDate} é confiável;</li>
 *   <li>não existe campo de cidade em lugar nenhum da oportunidade nem do cliente. A cidade só
 *       existe dentro do <b>nome</b> da oportunidade, por convenção da equipe — ver
 *       {@link #cidadeDoCliente()}.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OportunidadeNectar(
        Long id,
        String nome,
        String etapaNome,
        FunilVenda funilVenda,
        Pessoa cliente,
        Pessoa contatoPrincipal,
        Responsavel responsavel,
        String stageEntryDate,
        Map<String, Object> camposPersonalizados) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunilVenda(Long id, String nome) {
    }

    /**
     * Serve para {@code cliente} e {@code contatoPrincipal}, que vêm com a mesma forma (e, em
     * toda a carteira conferida, com o mesmo conteúdo).
     * <p>
     * {@code isEmpresa} existe na resposta e <b>não</b> é lido: vem {@code true} para pessoa
     * física na maior parte dos registros, então não distingue nada.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pessoa(Long id, String nome, String telefonePrincipal, String telefone,
            String emailPrincipal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Responsavel(Long id, String nome) {
    }

    /** Convenção de nome da oportunidade: {@code CLIENTE_CIDADE_VENDEDOR_VALOR_TENSÃO}. */
    private static final String SEPARADOR_DO_NOME = "_";

    /** Posição da cidade nessa convenção (base zero). */
    private static final int POSICAO_DA_CIDADE = 1;

    /**
     * O nome do cliente vem de {@code cliente.nome}. O nome da oportunidade é o último recurso,
     * para o registro não nascer anônimo — e a analista corrige na triagem.
     * <p>
     * ⚠️ <b>O campo de nome do cliente no Nectar costuma trazer o título inteiro da
     * oportunidade</b>, não o nome: "CLEIDE COQUEIRO MOREIRA_BRUMADO_JUDSON_R$4.000,00+20x..."
     * apareceu em 3 de 6 importações. Por isso o resultado passa pelo primeiro trecho da
     * convenção — o nome está sempre lá. A guarda é exigir <b>três ou mais</b> trechos separados
     * por {@code _}: nome de pessoa não tem isso, e assim um nome legítimo com um sublinhado
     * solto não é cortado.
     */
    public String nomeDoCliente() {
        String bruto = primeiroPreenchido(
                cliente == null ? null : cliente.nome(),
                contatoPrincipal == null ? null : contatoPrincipal.nome(),
                nome);
        return apenasONome(bruto);
    }

    /**
     * Tira o prefixo entre parênteses e devolve só o primeiro trecho, quando o texto tem a cara
     * do título de uma oportunidade. "(AMPLIAÇÃO 04) GELADÃO COMERCIO_BRUMADO_JUDSON_R$ 3.750"
     * vira "GELADÃO COMERCIO".
     */
    static String apenasONome(String bruto) {
        if (bruto == null) {
            return null;
        }
        String semPrefixo = bruto.replaceFirst("^\\s*\\([^)]*\\)\\s*", "").trim();
        String[] partes = semPrefixo.split(SEPARADOR_DO_NOME);
        if (partes.length < TRECHOS_MINIMOS_DA_CONVENCAO) {
            // Não parece título: devolve como está (já sem o prefixo, que nunca é nome).
            return semPrefixo.isBlank() ? null : semPrefixo;
        }
        String primeiro = partes[0].trim();
        return primeiro.isBlank() ? semPrefixo : primeiro;
    }

    /**
     * Quantos trechos o nome precisa ter para ser tratado como título da oportunidade
     * ({@code CLIENTE_CIDADE_VENDEDOR_...}). Três é o mínimo seguro: com dois, "Proposta_Comercial"
     * seria cortado para "Proposta".
     */
    private static final int TRECHOS_MINIMOS_DA_CONVENCAO = 3;

    /**
     * ⚠️ <b>Extraída do nome da oportunidade, por convenção da equipe</b>, porque não existe
     * campo de cidade na API — nem na oportunidade, nem no cliente, nem nos campos
     * personalizados. A equipe nomeia assim:
     * <pre>HUDSON OLIVEIRA SOUZA_VITÓRIA DA CONQUISTA_RODRIGO_3X + R$ 666,67_380/220V</pre>
     * então a cidade é o segundo trecho separado por {@code _}. Valeu para todas as
     * oportunidades conferidas, mas é convenção digitada por gente: pode vir errada, e a analista
     * corrige na triagem. Uma cidade errada não decide nada no fluxo — ela só aparece nas
     * listagens —, e o alternativo era alguém digitar a cidade de todo cliente à mão.
     * <p>
     * Nula quando o nome não segue a convenção, em vez de devolver um trecho qualquer.
     */
    public String cidadeDoCliente() {
        if (nome == null) {
            return null;
        }
        // O prefixo entre parênteses marca o tipo do caso — "(Ampliação)", "(PROJETO 3)" — e não
        // faz parte da convenção; descartá-lo mantém o cliente na primeira posição.
        String semPrefixo = nome.replaceFirst("^\\s*\\([^)]*\\)\\s*", "");
        String[] partes = semPrefixo.split(SEPARADOR_DO_NOME);
        if (partes.length <= POSICAO_DA_CIDADE) {
            return null;
        }
        String cidade = partes[POSICAO_DA_CIDADE].trim();
        // Nome de cidade sempre tem uma palavra de três letras ou mais. Trecho que não tem é
        // sinal de que a convenção não foi seguida e a segunda posição caiu no valor
        // ("R$ 1000 + 15X R$ 453,34") ou na tensão ("380/220V"), e aí é melhor nulo que lixo.
        // Uma regra só, em vez de uma lista de formatos a barrar: a lista sempre esquece um —
        // foi assim que "380/220V" passou na primeira versão desta guarda.
        return PALAVRA_DE_VERDADE.matcher(cidade).find() ? cidade : null;
    }

    private static final java.util.regex.Pattern PALAVRA_DE_VERDADE =
            java.util.regex.Pattern.compile("\\p{L}{3}");

    /** Celular/principal antes do fixo: é por ele que a operação fala com o cliente. */
    public String telefoneDoCliente() {
        return primeiroPreenchido(
                cliente == null ? null : cliente.telefonePrincipal(),
                cliente == null ? null : cliente.telefone(),
                contatoPrincipal == null ? null : contatoPrincipal.telefonePrincipal());
    }

    /** O vendedor do SolarSync é quem responde pelo negócio no CRM. */
    public String nomeDoVendedor() {
        return responsavel == null ? null : responsavel.nome();
    }

    public String nomeDoFunil() {
        return funilVenda == null ? null : funilVenda.nome();
    }

    /**
     * A {@code dataPagamento} do cliente, que é o marco zero da métrica "tempo médio sem ninguém
     * mexer no cliente" (seção 5).
     * <p>
     * Vem do campo personalizado <b>"Data do Pagamento"</b>, que a ConectSol mantém no funil
     * "5- Financeiro" — é literalmente o dado que se procura. Está preenchido em pouco mais da
     * metade dos negócios, então há um segundo caminho: {@code stageEntryDate}, o momento em que
     * a oportunidade entrou na etapa atual. Para a etapa "VALIDADO PELO FINANCEIRO" isso é
     * exatamente quando o financeiro validou, o que é o gatilho real da etapa 1 do fluxo.
     * <p>
     * {@code dataCriacao} <b>não</b> entra nem como terceiro recurso: vem corrompida em boa parte
     * da base (anos 0024, 0026, 0028) e uma data dessas faria a métrica de tempo parado render
     * dois mil anos.
     *
     * @param rotuloDoCampo nome do campo personalizado, configurável porque é rótulo digitado no
     *                      painel do Nectar
     */
    public LocalDate dataDePagamento(String rotuloDoCampo) {
        LocalDate doCampo = paraData(valorDoCampo(rotuloDoCampo));
        return doCampo != null ? doCampo : paraData(stageEntryDate);
    }

    private String valorDoCampo(String rotulo) {
        if (camposPersonalizados == null || rotulo == null) {
            return null;
        }
        Object valor = camposPersonalizados.get(rotulo);
        return valor == null ? null : String.valueOf(valor);
    }

    /**
     * Formato tolerante de propósito: o campo personalizado vem em {@code dd/MM/yyyy} (digitado)
     * e o {@code stageEntryDate} em ISO com hora. A consequência de não reconhecer é uma data
     * nula — que a analista preenche — e não um job que morre.
     */
    static LocalDate paraData(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        // Descarta a parte de hora, se houver: só a data interessa, e é ela que tem formato
        // variável. "2026-09-17T10:30:00Z" e "2026-09-17 10:30" caem os dois em "2026-09-17".
        String data = valor.trim().split("[T ]", 2)[0];
        for (DateTimeFormatter formato : FORMATOS) {
            try {
                return LocalDate.parse(data, formato);
            } catch (java.time.format.DateTimeParseException ignorada) {
                // Próximo formato.
            }
        }
        return null;
    }

    private static final List<DateTimeFormatter> FORMATOS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"));

    private static String primeiroPreenchido(String... valores) {
        for (String valor : valores) {
            if (valor != null && !valor.isBlank()) {
                return valor.trim();
            }
        }
        return null;
    }
}
