package com.conectsol.solarsync.integracao.gmail;

import java.time.Instant;

/**
 * Uma mensagem já reduzida ao que o parser da Coelba precisa: identidade, assunto, texto e
 * quando chegou. É a fronteira entre o transporte (Gmail) e a leitura do retorno da Coelba —
 * {@code ParserEmailCoelba} recebe isto e não conhece a API do Google, o que é o que permite
 * testá-lo com texto colado à mão.
 *
 * @param id         id da mensagem no Gmail. É a chave de idempotência gravada em
 *                   {@code email_coelba.mensagem_id}
 * @param assunto    cabeçalho {@code Subject}, guardado na trilha de auditoria porque é por ele
 *                   que uma pessoa reconhece o e-mail ao conferir o que a integração fez
 * @param texto      corpo em texto puro, já decodificado e com o HTML removido quando era o
 *                   caso. O assunto vem concatenado no começo: em muitos retornos da Coelba o
 *                   número da solicitação está no assunto e não no corpo
 * @param recebidoEm quando o Gmail recebeu a mensagem
 */
public record MensagemGmail(String id, String assunto, String texto, Instant recebidoEm) {
}
