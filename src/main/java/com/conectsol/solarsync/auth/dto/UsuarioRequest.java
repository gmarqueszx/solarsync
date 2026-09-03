package com.conectsol.solarsync.auth.dto;

import java.util.Set;

import com.conectsol.solarsync.auth.NomePapel;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * @param senha opcional: sem senha, o usuário entra apenas pelo Google (é o caminho normal
 *              aqui, já que a equipe usa Google Workspace)
 */
public record UsuarioRequest(
        @NotBlank @Size(max = 150) String nome,
        @NotBlank @Email @Size(max = 150) String email,
        @NotEmpty Set<NomePapel> papeis,
        @Size(min = 8, max = 100) String senha) {
}
