package com.conectsol.solarsync.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DefinirSenhaRequest(@NotBlank @Size(min = 8, max = 100) String senha) {
}
