package com.conectsol.solarsync.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.conectsol.solarsync.common.RepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;


@RepositoryTest
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
