package com.conectsol.solarsync.cliente;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.conectsol.solarsync.TestcontainersConfiguration;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
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
