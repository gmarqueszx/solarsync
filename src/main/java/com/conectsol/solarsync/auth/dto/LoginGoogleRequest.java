package com.conectsol.solarsync.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** ID token obtido pelo frontend via Google Identity Services. */
public record LoginGoogleRequest(@NotBlank String idToken) {
}
