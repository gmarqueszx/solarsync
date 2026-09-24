package com.conectsol.solarsync.debito.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.conectsol.solarsync.debito.StatusDebito;
import com.conectsol.solarsync.debito.TipoDebito;

import jakarta.validation.constraints.NotNull;

/**
 * Registra o resultado da consulta de débito na agência virtual da Coelba. Há no máximo um
 * débito por cliente <b>por tipo</b> (constraint no banco): consultar de novo atualiza o mesmo
 * registro, o que mantém o histórico de idas e vindas em {@code historico_status} em vez de
 * espalhado em linhas.
 *
 * @param tipo         que etapa esta consulta responde — {@code PENDENCIA} para o débito que
 *                     impede a Coelba de resolver a pendência, {@code HOMOLOGACAO} para o que
 *                     impede o envio do projeto. Obrigatório: sem ele a consulta não diz o que
 *                     está travando, que é a pergunta da tela
 * @param consultadoEm       quando a consulta foi feita; ausente, assume agora
 * @param proximoVencimento  vencimento da próxima conta, visto na mesma consulta. Opcional, e só
 *                           considerado quando o {@code status} é {@code QUITADO} — com débito
 *                           ATIVO não há "próxima", há a atual. Nulo é "não informado", que é um
 *                           estado legítimo e diferente de "não existe próxima conta".
 *                           <p>
 *                           Faltando um dia ou menos para esta data, encaminhar o projeto à
 *                           Coelba passa a ser recusado com 409 {@code PROXIMO_DEBITO_A_VENCER}
 *                           ({@code ProjetoService})
 */
public record DebitoRegistrarRequest(
        @NotNull TipoDebito tipo,
        @NotNull StatusDebito status,
        Instant consultadoEm,
        LocalDate proximoVencimento) {
}
