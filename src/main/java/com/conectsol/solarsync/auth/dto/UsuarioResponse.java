package com.conectsol.solarsync.auth.dto;

import java.util.List;

import com.conectsol.solarsync.auth.NomePapel;
import com.conectsol.solarsync.auth.Papel;
import com.conectsol.solarsync.auth.Usuario;

/** Nunca expõe {@code senhaHash}. */
public record UsuarioResponse(
        Long id,
        String nome,
        String email,
        boolean ativo,
        boolean temSenha,
        List<NomePapel> papeis) {

    public static UsuarioResponse de(Usuario usuario) {
        return new UsuarioResponse(
                usuario.getId(),
                usuario.getNome(),
                usuario.getEmail(),
                usuario.isAtivo(),
                usuario.getSenhaHash() != null,
                usuario.getPapeis().stream().map(Papel::getNome).sorted().toList());
    }
}
