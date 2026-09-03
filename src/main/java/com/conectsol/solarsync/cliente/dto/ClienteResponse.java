package com.conectsol.solarsync.cliente.dto;

import java.time.LocalDate;

import com.conectsol.solarsync.cliente.Cliente;

public record ClienteResponse(
        Long id,
        String nome,
        String cidade,
        String vendedor,
        LocalDate dataPagamento) {

    public static ClienteResponse de(Cliente cliente) {
        return new ClienteResponse(
                cliente.getId(),
                cliente.getNome(),
                cliente.getCidade(),
                cliente.getVendedor(),
                cliente.getDataPagamento());
    }
}
