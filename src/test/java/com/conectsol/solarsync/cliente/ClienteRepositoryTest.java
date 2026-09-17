package com.conectsol.solarsync.cliente;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.conectsol.solarsync.common.RepositoryTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.beans.factory.annotation.Autowired;


@RepositoryTest
class ClienteRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ClienteRepository clienteRepository;

    @Test
    void persisteERecuperaCliente() {
        Cliente cliente = Cliente.builder()
                .nome("João da Silva")
                .cidade("Salvador")
                .vendedor("Maria Vendedora")
                .dataPagamento(LocalDate.of(2026, 1, 15))
                .build();

        Cliente salvo = clienteRepository.save(cliente);
        entityManager.flush();
        entityManager.clear();

        Cliente encontrado = clienteRepository.findById(salvo.getId()).orElseThrow();
        assertThat(encontrado.getNome()).isEqualTo("João da Silva");
        assertThat(encontrado.getCidade()).isEqualTo("Salvador");
        assertThat(encontrado.getCriadoEm()).isNotNull();
        assertThat(encontrado.getAtualizadoEm()).isNotNull();
    }
}
