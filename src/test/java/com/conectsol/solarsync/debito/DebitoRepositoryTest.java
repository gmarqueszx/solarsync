package com.conectsol.solarsync.debito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

import com.conectsol.solarsync.TestcontainersConfiguration;
import com.conectsol.solarsync.cliente.Cliente;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DebitoRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DebitoRepository debitoRepository;

    private Cliente novoCliente() {
        Cliente cliente = Cliente.builder().nome("Cliente Débito").build();
        return entityManager.persistFlushFind(cliente);
    }

    @Test
    void persisteERecuperaDebito() {
        Cliente cliente = novoCliente();

        Debito debito = Debito.builder()
                .cliente(cliente)
                .status(StatusDebito.ATIVO)
                .ultimaConsultaEm(Instant.now())
                .build();

        debitoRepository.save(debito);
        entityManager.flush();
        entityManager.clear();

        Debito encontrado = debitoRepository.findByClienteId(cliente.getId()).orElseThrow();
        assertThat(encontrado.getStatus()).isEqualTo(StatusDebito.ATIVO);
    }

    @Test
    void rejeitaDoisDebitosParaOMesmoCliente() {
        Cliente cliente = novoCliente();
        debitoRepository.save(Debito.builder().cliente(cliente).status(StatusDebito.ATIVO).build());
        entityManager.flush();

        assertThatThrownBy(() -> {
            debitoRepository.save(Debito.builder().cliente(cliente).status(StatusDebito.QUITADO).build());
            entityManager.flush();
        }).isInstanceOf(DataAccessException.class);
    }
}
