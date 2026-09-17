package com.conectsol.solarsync.pendencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.conectsol.solarsync.common.RepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.conectsol.solarsync.cliente.Cliente;

@RepositoryTest
class PendenciaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PendenciaRepository pendenciaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Cliente novoCliente() {
        Cliente cliente = Cliente.builder().nome("Cliente Teste").build();
        return entityManager.persistFlushFind(cliente);
    }

    @Test
    void persisteERecuperaPendencia() {
        Cliente cliente = novoCliente();

        Pendencia pendencia = Pendencia.builder()
                .cliente(cliente)
                .tipo(TipoPendencia.TROCA_TITULARIDADE)
                .status(StatusPendencia.ABERTA)
                .solicitadoEm(Instant.now())
                .observacao("Aguardando documentação")
                .build();

        Pendencia salva = pendenciaRepository.save(pendencia);
        entityManager.flush();
        entityManager.clear();

        Pendencia encontrada = pendenciaRepository.findById(salva.getId()).orElseThrow();
        assertThat(encontrada.getStatus()).isEqualTo(StatusPendencia.ABERTA);
        assertThat(encontrada.getTipo()).isEqualTo(TipoPendencia.TROCA_TITULARIDADE);
        assertThat(encontrada.getCliente().getId()).isEqualTo(cliente.getId());
    }

    @Test
    void rejeitaStatusForaDoDominioNoBanco() {
        Cliente cliente = novoCliente();
        entityManager.flush();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO pendencia (cliente_id, tipo, status, solicitado_em) VALUES (?, ?, ?, now())",
                cliente.getId(), "TROCA_TITULARIDADE", "STATUS_INEXISTENTE"))
                .isInstanceOf(DataAccessException.class);
    }
}
