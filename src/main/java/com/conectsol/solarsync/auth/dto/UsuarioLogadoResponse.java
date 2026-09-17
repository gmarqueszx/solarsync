package com.conectsol.solarsync.auth.dto;

import java.util.List;

import com.conectsol.solarsync.auth.NomePapel;
import com.conectsol.solarsync.auth.Papel;
import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.common.security.UsuarioAutenticado;

/**
 * O frontend usa os papéis para montar a sidebar e esconder ações que o usuário não pode
 * executar. Vem da API em vez de ser lido do JWT no cliente, que é frágil.
 */
public record UsuarioLogadoResponse(Long id, String nome, String email, List<NomePapel> papeis) {

    public static UsuarioLogadoResponse de(Usuario usuario) {
        return new UsuarioLogadoResponse(
                usuario.getId(),
                usuario.getNome(),
                usuario.getEmail(),
                usuario.getPapeis().stream().map(Papel::getNome).sorted().toList());
    }

    public static UsuarioLogadoResponse de(UsuarioAutenticado usuario) {
        return new UsuarioLogadoResponse(
                usuario.id(),
                usuario.nome(),
                usuario.email(),
                usuario.papeis().stream().sorted().toList());
    }
}
