package com.conectsol.solarsync.auth.dto;

import com.conectsol.solarsync.auth.Usuario;

/**
 * Reutilizado por Pendência e Projeto (responsável / analista) e pelo lookup que popula os
 * seletores do frontend.
 */
public record UsuarioResumoResponse(Long id, String nome) {

    public static UsuarioResumoResponse de(Usuario usuario) {
        return usuario == null ? null
                : new UsuarioResumoResponse(usuario.getId(), usuario.getNome());
    }
}
