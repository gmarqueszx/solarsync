package com.conectsol.solarsync.auth.dto;

import java.util.Set;

import com.conectsol.solarsync.auth.NomePapel;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Cadastro de usuário, feito por ADMINISTRADOR ou GESTOR.
 * <p>
 * A <b>senha é obrigatória</b> desde a remoção do login Google (16/09/2026): antes ela era
 * opcional porque quem não tinha senha entrava pelo Google, e agora um usuário sem senha seria
 * uma conta que não consegue entrar por caminho nenhum. Quem recebe a conta troca a senha em
 * {@code POST /api/usuarios/{id}/senha}.
 */
public record UsuarioCriarRequest(
        @NotBlank @Size(max = 150) String nome,
        @NotBlank @Email @Size(max = 150) String email,
        @NotEmpty Set<NomePapel> papeis,
        @NotBlank @Size(min = 8, max = 100) String senha) {
}
