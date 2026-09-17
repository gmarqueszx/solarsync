package com.conectsol.solarsync.auth.dto;

import java.util.Set;

import com.conectsol.solarsync.auth.NomePapel;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Edição do cadastro. <b>Sem campo senha</b>: trocar senha é
 * {@code POST /api/usuarios/{id}/senha}, e aceitá-la aqui faria um formulário de correção de
 * nome resetar a senha de quem esqueceu de preencher o campo.
 */
public record UsuarioAtualizarRequest(
        @NotBlank @Size(max = 150) String nome,
        @NotBlank @Email @Size(max = 150) String email,
        @NotEmpty Set<NomePapel> papeis) {
}
