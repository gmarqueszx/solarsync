package com.conectsol.solarsync.cliente.dto;

import com.conectsol.solarsync.cliente.Cliente;

/** Reutilizado dentro das respostas de Pendência e Projeto, em vez do Cliente inteiro. */
public record ClienteResumoResponse(Long id, String nome, String cidade, String ucCoelba) {

    public static ClienteResumoResponse de(Cliente cliente) {
        return cliente == null ? null
                : new ClienteResumoResponse(cliente.getId(), cliente.getNome(), cliente.getCidade(),
                        cliente.getUcCoelba());
    }
}
