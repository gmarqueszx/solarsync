package com.conectsol.solarsync.common.security;

import java.util.List;
import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;

import com.conectsol.solarsync.auth.NomePapel;

/**
 * Identidade de quem está fazendo a requisição, montada a partir dos claims do JWT. Vive em
 * {@code common} — e não em {@code auth} — para os controllers dos módulos operacionais não
 * dependerem do módulo de identidade.
 */
public record UsuarioAutenticado(Long id, String email, String nome, Set<NomePapel> papeis) {

    public static UsuarioAutenticado de(Jwt token) {
        List<String> papeis = token.getClaimAsStringList("papeis");
        return new UsuarioAutenticado(
                Long.valueOf(token.getSubject()),
                token.getClaimAsString("email"),
                token.getClaimAsString("nome"),
                papeis == null ? Set.of()
                        : papeis.stream().map(NomePapel::valueOf).collect(
                                java.util.stream.Collectors.toUnmodifiableSet()));
    }

    public boolean tem(NomePapel papel) {
        return papeis.contains(papel);
    }
}
