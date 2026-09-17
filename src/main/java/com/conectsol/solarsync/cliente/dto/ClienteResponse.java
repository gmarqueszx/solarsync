package com.conectsol.solarsync.cliente.dto;

import java.time.LocalDate;

import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.StatusTriagem;

/**
 * @param statusTriagem resultado da checagem de pendência na Coelba. É o que permite ao
 *                      frontend montar a fila "falta checar" em vez de deduzi-la da ausência
 *                      de registros
 */
public record ClienteResponse(
        Long id,
        String nome,
        String cidade,
        String vendedor,
        LocalDate dataPagamento,
        String ucCoelba,
        String telefone,
        StatusTriagem statusTriagem) {

    public static ClienteResponse de(Cliente cliente) {
        return new ClienteResponse(
                cliente.getId(),
                cliente.getNome(),
                cliente.getCidade(),
                cliente.getVendedor(),
                cliente.getDataPagamento(),
                cliente.getUcCoelba(),
                cliente.getTelefone(),
                cliente.getStatusTriagem());
    }
}
