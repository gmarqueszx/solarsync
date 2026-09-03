package com.conectsol.solarsync.auth.google;

/**
 * Primeira barreira do login Google: prova criptográfica de que o ID token foi emitido pelo
 * Google para a nossa aplicação. Interface para o serviço de login ser testável sem rede.
 */
public interface VerificadorIdTokenGoogle {

    /**
     * @throws com.conectsol.solarsync.common.exception.UsuarioNaoAutorizadoException se a
     *         assinatura, o emissor, a audiência ou a validade não conferirem
     */
    IdentidadeGoogle verificar(String idToken);

    record IdentidadeGoogle(String email, boolean emailVerificado, String hostedDomain, String nome) {
    }
}
