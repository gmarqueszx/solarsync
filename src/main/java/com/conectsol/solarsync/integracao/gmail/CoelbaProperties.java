package com.conectsol.solarsync.integracao.gmail;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Como reconhecer o resultado no e-mail do Portal da Geração Distribuída da Neoenergia. É
 * configuração, e não código, porque o formato do e-mail é de terceiro: a Neoenergia muda a
 * redação sem avisar, e ajustar uma propriedade é mais rápido que um deploy.
 * <p>
 * Tudo aqui é comparado <b>sem acento e sem caixa</b>, então não precisa (nem deve) haver
 * variante acentuada nas listas: {@code em analise} casa "Em Análise Técnica".
 *
 * <h2>Os dois caminhos, e por que o de etapa vem primeiro</h2>
 *
 * O e-mail real (conferido em 19/09/2026, três exemplares de {@code noreplyportalgd@neoenergia.com})
 * é uma <b>notificação de mudança de etapa</b>, e o resultado não está em adjetivo nenhum:
 *
 * <pre>
 * Prezado(a) FULANO,
 * Sua solicitação de acesso à rede ... passou para uma nova etapa. Informamos que as
 * informações e documentação foram recebidas e serão avaliadas pela distribuidora.
 * Número da solicitação: 2608198955
 * Etapa anterior: Em Análise Técnica
 * Etapa atual: Aguardando solicitação de vistoria e Conexão
 * </pre>
 *
 * ⚠️ Aquele parágrafo do meio é <b>idêntico</b> no e-mail que confirma o envio e no que anuncia
 * a aprovação. Quem lê o corpo livre não distingue os dois — só a linha {@code Etapa atual}
 * distingue. Por isso ela tem precedência absoluta, e por isso um e-mail com
 * {@code Etapa atual} de valor desconhecido cai como não reconhecido em vez de voltar para a
 * busca por termos: naquele corpo, procurar termo solto é ler o texto que não diz nada.
 * <p>
 * ⚠️ E é por isso também que se lê {@code Etapa atual} e nunca {@code Etapa anterior}: no e-mail
 * de aprovação, a etapa <i>anterior</i> é "Em Análise Técnica". Um parser que procurasse
 * "em análise" no texto inteiro classificaria toda aprovação como acompanhamento sem decisão — e
 * o projeto ficaria parado em ENCAMINHADO para sempre, sem erro em lugar nenhum.
 * <p>
 * As listas de <b>termos</b> continuam existindo como segundo caminho, para o e-mail que
 * <b>não</b> traz {@code Etapa atual}. O cancelamento é justamente esse caso: ele não anuncia
 * etapa, anuncia "Sua solicitação ... foi cancelada" com o motivo numa linha própria.
 *
 * @param rotuloEtapaAtual  o rótulo que precede a etapa. Lido em qualquer ponto da linha, e não
 *                          só no começo, porque a conversão de HTML para texto nem sempre
 *                          preserva a quebra de linha original
 * @param etapasAprovado    valores de {@code Etapa atual} que significam projeto aprovado. O
 *                          padrão é o observado: passar para "Aguardando solicitação de vistoria
 *                          e Conexão" <b>é</b> a aprovação técnica, e é exatamente o ponto em
 *                          que o SolarSync manda o projeto para a fila da Vistoria
 * @param etapasReprovado   idem para reprovação
 * @param etapasVistoriaSolicitada etapas que significam "a Coelba está realizando a vistoria",
 *                          logo ela foi solicitada. O padrão saiu do fluxo real
 * @param etapasVistoriaAprovada   etapas que significam vistoria aprovada
 * @param etapasEmAnalise   etapas que não decidem nada. Não disparam mudança: projeto em análise
 *                          já está ENCAMINHADO, que é o status correto. Distinguem
 *                          "acompanhamento, nada a fazer" de "não entendi"
 * @param rotuloMotivo      rótulo da linha com o motivo, usada como {@code motivoReprova} do
 *                          projeto. Sem ela o motivo sairia do recorte genérico em volta do
 *                          termo, que traz junto o cabeçalho e o link de acompanhamento
 * @param termosAprovado    segundo caminho: presença isolada de qualquer um deles vale como
 *                          aprovação, num e-mail sem {@code Etapa atual}
 * @param termosReprovado   idem para reprovação. {@code cancelad} é o termo do e-mail real
 * @param termosEmAnalise   idem para acompanhamento
 * @param minimoDigitos     tamanho mínimo de um número para ser candidato a número de
 *                          solicitação. Baixo de propósito: quem garante que o número é o certo
 *                          não é o formato, é o casamento com um projeto existente
 * @param maximoCandidatos  teto de números considerados num e-mail, para um corpo cheio de
 *                          números não virar dezenas de consultas
 */
@ConfigurationProperties("solarsync.coelba")
public record CoelbaProperties(
        String rotuloEtapaAtual,
        List<String> etapasAprovado,
        List<String> etapasReprovado,
        List<String> etapasVistoriaSolicitada,
        List<String> etapasVistoriaAprovada,
        List<String> etapasEmAnalise,
        String rotuloMotivo,
        List<String> termosAprovado,
        List<String> termosReprovado,
        List<String> termosEmAnalise,
        int minimoDigitos,
        int maximoCandidatos) {

    public CoelbaProperties {
        rotuloEtapaAtual = ouPadrao(rotuloEtapaAtual, "Etapa atual");
        rotuloMotivo = ouPadrao(rotuloMotivo, "Motivo do cancelamento");

        etapasAprovado = ouPadrao(etapasAprovado, List.of(
                "aguardando solicitacao de vistoria"));
        etapasReprovado = ouPadrao(etapasReprovado, List.of(
                "cancelad", "indeferid", "reprovad"));
        etapasVistoriaSolicitada = ouPadrao(etapasVistoriaSolicitada, List.of(
                "realizando vistoria"));
        etapasVistoriaAprovada = ouPadrao(etapasVistoriaAprovada, List.of(
                "ponto de conexao aprovado"));
        // Etapas que não decidem nada. "Solicitação Concluída" está aqui, e não como aprovação
        // da vistoria, por decisão do usuário: quem fecha a vistoria é o ponto de conexão
        // aprovado, e o encerramento da solicitação vem depois disso sem acrescentar informação.
        // As outras precisam ser reconhecidas para o e-mail mais frequente do fluxo não aparecer
        // como não reconhecido a cada execução, escondendo os casos reais.
        etapasEmAnalise = ouPadrao(etapasEmAnalise, List.of(
                "em analise", "aguardando documentacao", "aguardando complementacao",
                "solicitacao concluida"));

        termosAprovado = ouPadrao(termosAprovado, List.of(
                "aprovad", "deferid", "homologad"));
        termosReprovado = ouPadrao(termosReprovado, List.of(
                "cancelad", "reprovad", "indeferid", "rejeitad", "nao conformidade"));
        // "etapa de estudos" cobre a variante em prosa do aceite da documentação, que é 20% do
        // volume real e não traz linha de etapa nenhuma — sem ela, um e-mail em cada cinco
        // cairia como não reconhecido para sempre.
        termosEmAnalise = ouPadrao(termosEmAnalise, List.of(
                "em analise", "em avaliacao", "aguardando analise", "etapa de estudos"));

        minimoDigitos = minimoDigitos <= 0 ? 5 : minimoDigitos;
        maximoCandidatos = maximoCandidatos <= 0 ? 20 : maximoCandidatos;
    }

    private static List<String> ouPadrao(List<String> configurado, List<String> padrao) {
        return configurado == null || configurado.isEmpty() ? padrao : configurado;
    }

    private static String ouPadrao(String configurado, String padrao) {
        return configurado == null || configurado.isBlank() ? padrao : configurado;
    }
}
