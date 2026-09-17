package com.conectsol.solarsync.common.exception;

import com.conectsol.solarsync.debito.TipoDebito;

/**
 * Nunca houve consulta de débito daquele tipo registrada para o cliente, então não se sabe se
 * ele deve — e avançar a etapa exige saber. Vira 409.
 * <p>
 * Decisão do usuário: "para ela saber se o cliente tem débito ou não é necessário consultar".
 * Cliente sem consulta não é cliente sem débito; é cliente que ninguém olhou, que é justamente
 * o caso perigoso.
 * <p>
 * Vale nas <b>duas</b> etapas, cada uma exigindo a consulta do seu tipo: {@code PENDENCIA} para
 * resolver a pendência, {@code HOMOLOGACAO} para encaminhar o projeto à Coelba. Isto reverteu a
 * assimetria anterior, em que o envio à Coelba tratava "sem consulta" como sem débito — na
 * operação real é o projetista quem consulta o débito ao receber o cliente, e essa consulta é
 * exatamente o passo que faltava ser exigido.
 */
public class DebitoNaoConsultadoException extends RuntimeException {

    public DebitoNaoConsultadoException(Long clienteId, TipoDebito tipo) {
        super(("Nenhuma consulta de débito de %s registrada para o cliente %d; consulte a "
                + "agência virtual e registre o resultado antes de %s")
                .formatted(tipo, clienteId, acao(tipo)));
    }

    private static String acao(TipoDebito tipo) {
        return tipo == TipoDebito.PENDENCIA
                ? "resolver a pendência"
                : "encaminhar o projeto";
    }
}
