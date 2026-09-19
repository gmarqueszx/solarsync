package com.conectsol.solarsync.cliente.dto;

import java.time.LocalDate;

import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.OrigemCliente;
import com.conectsol.solarsync.cliente.StatusTriagem;

/**
 * @param statusTriagem        resultado da checagem de pendência na Coelba. É o que permite ao
 *                             frontend montar a fila "falta checar" em vez de deduzi-la da
 *                             ausência de registros
 * @param origem               {@code MANUAL} ou {@code CRM_NECTAR}. A triagem mostra isso porque
 *                             a confiança nos dados é diferente: no cliente do CRM, campo vazio
 *                             significa "o CRM não sabia" e pede preenchimento; no manual, é
 *                             esquecimento. Campo próprio em vez de o frontend deduzir de
 *                             {@code nectarOportunidadeId != null} — a regra fica num lugar só
 * @param nectarOportunidadeId o id da oportunidade no Nectar, quando veio de lá. Serve para
 *                             achar o negócio no CRM a partir do cliente
 */
public record ClienteResponse(
        Long id,
        String nome,
        String cidade,
        String vendedor,
        LocalDate dataPagamento,
        String ucCoelba,
        String telefone,
        StatusTriagem statusTriagem,
        OrigemCliente origem,
        String nectarOportunidadeId) {

    public static ClienteResponse de(Cliente cliente) {
        return new ClienteResponse(
                cliente.getId(),
                cliente.getNome(),
                cliente.getCidade(),
                cliente.getVendedor(),
                cliente.getDataPagamento(),
                cliente.getUcCoelba(),
                cliente.getTelefone(),
                cliente.getStatusTriagem(),
                OrigemCliente.de(cliente),
                cliente.getNectarOportunidadeId());
    }
}
