package com.conectsol.solarsync.common.exception;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * O cliente está quitado hoje, mas a próxima conta vence em um dia ou menos — e projeto
 * encaminhado nessa véspera volta reprovado da Coelba, porque quando ela for analisar já existe
 * débito. Vira 409, como as outras guardas de envio.
 * <p>
 * É a terceira recusa do envio à Coelba, e as três são propositalmente distinguíveis pelo
 * {@code codigo}, porque cada uma pede uma ação diferente de quem está na tela:
 * {@code DEBITO_NAO_CONSULTADO} pede a consulta na agência virtual, {@code CLIENTE_COM_DEBITO}
 * pede a cobrança, e esta aqui pede <b>esperar</b> — não há nada a cobrar, a conta ainda nem
 * venceu.
 */
public class ProximoDebitoAVencerException extends RuntimeException {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public ProximoDebitoAVencerException(Long clienteId, LocalDate vencimento, long diasRestantes) {
        super(("Cliente %d tem o próximo débito vencendo em %s (%s); encaminhar agora faz a Coelba "
                + "analisar o projeto já com débito em aberto. Aguarde a quitação.")
                .formatted(clienteId, vencimento.format(DATA), emQuantoTempo(diasRestantes)));
    }

    /**
     * Texto por extenso em vez do número cru: "faltam -3 dias" é o caso mais comum aqui — a data
     * informada na última consulta costuma já ter passado quando alguém tenta enviar — e seria
     * lido como erro do sistema em vez de como conta vencida.
     */
    private static String emQuantoTempo(long dias) {
        if (dias < 0) {
            return "venceu há %d dia(s)".formatted(-dias);
        }
        return dias == 0 ? "vence hoje" : "falta %d dia".formatted(dias);
    }
}
