package com.conectsol.solarsync.common.exception;

/**
 * O cliente foi marcado como de fluxo curto ("somente pendência") e alguém tentou criar um
 * projeto para ele. Vira 409: o pedido é válido, o que não bate é o desenho do fluxo daquele
 * cliente.
 * <p>
 * A guarda fica no {@code ProjetoService}, e não nos listeners que criam o projeto
 * automaticamente, para valer igual venha a criação da tela, da resolução da pendência, da
 * triagem sem pendência ou de uma importação — que é a mesma razão pela qual a guarda de débito
 * mora lá.
 * <p>
 * Não é beco sem saída: desmarcar "somente pendência" no cadastro devolve o cliente ao fluxo
 * completo. É o caminho para o avulso que, no meio do caminho, virou projeto de verdade.
 */
public class ClienteSomentePendenciaException extends RuntimeException {

    public ClienteSomentePendenciaException(Long clienteId) {
        super(("Cliente %d está no fluxo \"somente pendência\": o processo dele termina quando a "
                + "pendência é resolvida. Desmarque essa opção no cadastro para abrir projeto.")
                .formatted(clienteId));
    }
}
