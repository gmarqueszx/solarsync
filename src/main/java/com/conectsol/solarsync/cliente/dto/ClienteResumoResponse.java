package com.conectsol.solarsync.cliente.dto;

import com.conectsol.solarsync.cliente.Cliente;

/**
 * Reutilizado dentro das respostas de Pendência, Débito, Projeto, Vistoria e Unificação, em vez
 * do Cliente inteiro.
 * <p>
 * {@code prioridade} e {@code somentePendencia} entram aqui apesar de o resumo ser enxuto porque
 * são as duas informações que mudam o que a tela <b>faz</b> com a linha, não só o que mostra: a
 * prioridade decide a posição na fila, e o fluxo curto decide se aquele cliente ainda tem etapas
 * pela frente. Buscá-las em outra requisição seria uma ida ao servidor por linha.
 */
public record ClienteResumoResponse(
        Long id,
        String nome,
        String cidade,
        String ucCoelba,
        boolean prioridade,
        boolean somentePendencia) {

    public static ClienteResumoResponse de(Cliente cliente) {
        return cliente == null ? null
                : new ClienteResumoResponse(cliente.getId(), cliente.getNome(), cliente.getCidade(),
                        cliente.getUcCoelba(), cliente.isPrioridade(),
                        cliente.isSomentePendencia());
    }
}
