package com.conectsol.solarsync.common;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * O fuso em que a ConectSol trabalha. Num lugar só porque duas regras diferentes dependem dele e
 * erram em silêncio se divergirem.
 * <p>
 * ⚠️ <b>Não use {@code LocalDate.now()} para comparar com data de negócio.</b> Ele lê o fuso do
 * servidor, que num VPS é UTC: às 21h de Salvador já é o dia seguinte em UTC, e a conta "faltam
 * quantos dias para o próximo débito vencer" daria um dia a menos justamente na janela em que a
 * resposta muda de "pode enviar" para "não pode".
 * <p>
 * A mesma razão pela qual a leitura do e-mail da Coelba converte a data da mensagem para cá antes
 * de gravar {@code data_aprovacao}.
 */
public final class FusoDaOperacao {

    public static final ZoneId ZONA = ZoneId.of("America/Bahia");

    private FusoDaOperacao() {
    }

    /** O "hoje" da operação, que é o único hoje que as regras de negócio deste sistema conhecem. */
    public static LocalDate hoje() {
        return LocalDate.now(ZONA);
    }
}
