package com.conectsol.solarsync.unificacao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import com.conectsol.solarsync.TestcontainersConfiguration;
import com.conectsol.solarsync.cliente.Cliente;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UnificacaoRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UnificacaoRepository unificacaoRepository;

    @Test
    void persisteERecuperaUnificacao() {
        Cliente cliente = entityManager.persistFlushFind(Cliente.builder().nome("Cliente Unificação").build());

        Unificacao unificacao = Unificacao.builder()
                .cliente(cliente)
                .cidade("Feira de Santana")
                .informacoes("Unificação de duas unidades consumidoras")
                .feita(false)
                .desligamento(false)
                .build();

        Unificacao salva = unificacaoRepository.save(unificacao);
        entityManager.flush();
        entityManager.clear();

        Unificacao encontrada = unificacaoRepository.findById(salva.getId()).orElseThrow();
        assertThat(encontrada.isFeita()).isFalse();
        assertThat(encontrada.getCliente().getId()).isEqualTo(cliente.getId());
    }
}
