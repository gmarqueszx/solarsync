package com.conectsol.solarsync.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.conectsol.solarsync.TestcontainersConfiguration;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PapelRepositoryTest {

    @Autowired
    private PapelRepository papelRepository;

    @Test
    void encontraOsTresPapeisSemeadosPelaMigration() {
        assertThat(papelRepository.findByNome(NomePapel.ADMINISTRADOR)).isPresent();
        assertThat(papelRepository.findByNome(NomePapel.GESTOR)).isPresent();
        assertThat(papelRepository.findByNome(NomePapel.ANALISTA)).isPresent();
        assertThat(papelRepository.findAll()).hasSize(3);
    }
}
