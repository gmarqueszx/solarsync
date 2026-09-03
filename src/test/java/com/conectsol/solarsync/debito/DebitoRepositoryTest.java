package com.conectsol.solarsync.debito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.conectsol.solarsync.common.RepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataAccessException;

import com.conectsol.solarsync.cliente.Cliente;

@RepositoryTest
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
