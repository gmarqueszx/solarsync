package com.conectsol.solarsync.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import com.conectsol.solarsync.TestcontainersConfiguration;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
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
