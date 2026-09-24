package com.conectsol.solarsync.integracao.gmail;

import java.util.List;

/**
 * O que {@link ParserEmailCoelba} conseguiu ler de um e-mail. Tudo aqui é leitura de texto:
 * nada foi conferido contra o banco ainda, e é {@code RetornoCoelbaService} que decide se existe
 * projeto correspondente.
 *
 * @param resultado  {@code null} quando o texto não permitiu concluir nada — inclusive quando
 *                   permitiu concluir <b>duas</b> coisas (aprovação e reprovação no mesmo
 *                   e-mail). Nulo é a resposta segura: o job registra o e-mail como não
 *                   reconhecido e não toca no projeto
 * @param numeros    números candidatos a número de solicitação, na ordem em que aparecem e sem
 *                   pontuação. São candidatos e não certezas: quem confirma é o casamento com um
 *                   projeto existente
 * @param motivo     texto em volta do termo de reprovação, para virar o {@code motivoReprova} do
 *                   projeto. Nulo quando o resultado não é reprovação
 * @param ambiguo    o e-mail afirmava aprovação e reprovação ao mesmo tempo. Distinguido de
 *                   "não entendi nada" porque a causa e a correção são diferentes: aqui as
 *                   listas de termos precisam de ajuste, lá provavelmente é outro assunto
 */
public record RetornoCoelba(
        ResultadoCoelba resultado,
        List<String> numeros,
        String motivo,
        boolean ambiguo) {

    static RetornoCoelba naoReconhecido(List<String> numeros) {
        return new RetornoCoelba(null, numeros, null, false);
    }

    static RetornoCoelba ambiguo(List<String> numeros) {
        return new RetornoCoelba(null, numeros, null, true);
    }

    static RetornoCoelba de(ResultadoCoelba resultado, List<String> numeros, String motivo) {
        return new RetornoCoelba(resultado, numeros, motivo, false);
    }
}
