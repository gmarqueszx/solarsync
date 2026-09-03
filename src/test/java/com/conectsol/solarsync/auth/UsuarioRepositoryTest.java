package com.conectsol.solarsync.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.conectsol.solarsync.common.RepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;


@RepositoryTest
class UsuarioRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Test
    void persisteEBuscaPorEmail() {
        Usuario usuario = Usuario.builder()
                .nome("Igor Gestor")
                .email("igor@conectsol.com")
                .build();

        usuarioRepository.save(usuario);
        entityManager.flush();
        entityManager.clear();

        Usuario encontrado = usuarioRepository.findByEmail("igor@conectsol.com").orElseThrow();
        assertThat(encontrado.getNome()).isEqualTo("Igor Gestor");
        assertThat(encontrado.isAtivo()).isTrue();
    }
}
