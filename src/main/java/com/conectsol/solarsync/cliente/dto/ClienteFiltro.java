package com.conectsol.solarsync.cliente.dto;

import java.util.List;

import com.conectsol.solarsync.cliente.StatusTriagem;

/**
 * @param nome               casa nome <b>ou</b> UC Coelba — o analista digita os dois no mesmo
 *                           campo. O parâmetro se chama {@code nome} por compatibilidade: é o
 *                           nome que já está no contrato commitado e no frontend
 * @param statusTriagem      a fila da triagem sai daqui:
 *                           {@code ?statusTriagem=AGUARDANDO_VERIFICACAO} é a lista de quem
 *                           ninguém checou na Coelba ainda
 * @param semConsultaDebito  clientes sem nenhuma consulta de débito registrada — a fila da
 *                           "consulta de débito pré projeto". Serve às duas pontas: sem ela a
 *                           analista não consegue resolver pendência, e sem ela o projeto vai
 *                           para a Coelba sem ninguém ter olhado a agência virtual
 */
public record ClienteFiltro(
        String nome,
        List<StatusTriagem> statusTriagem,
        Boolean semConsultaDebito) {
}
