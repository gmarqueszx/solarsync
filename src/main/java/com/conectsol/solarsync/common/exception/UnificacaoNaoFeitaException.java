package com.conectsol.solarsync.common.exception;

/**
 * O desligamento do medidor só é pedido depois de confirmada a unificação: terminada a
 * instalação, confere-se se a unificação foi feita e, se sim, solicita-se o desligamento do
 * medidor unificado. Pedir antes desligaria o medidor de um cliente que ainda depende dele.
 */
public class UnificacaoNaoFeitaException extends RuntimeException {

    public UnificacaoNaoFeitaException(Long unificacaoId) {
        super(("Unificação %d ainda não foi concluída; confirme a unificação antes de "
                + "solicitar o desligamento do medidor").formatted(unificacaoId));
    }
}
