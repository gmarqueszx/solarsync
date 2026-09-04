package com.conectsol.solarsync.unificacao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.conectsol.solarsync.common.RepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.conectsol.solarsync.cliente.Cliente;

@RepositoryTest
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
                .desligamentoStatus(StatusDesligamento.NAO_SOLICITADO)
                .build();

        Unificacao salva = unificacaoRepository.save(unificacao);
        entityManager.flush();
        entityManager.clear();

        Unificacao encontrada = unificacaoRepository.findById(salva.getId()).orElseThrow();
        assertThat(encontrada.isFeita()).isFalse();
        assertThat(encontrada.getDesligamentoStatus())
                .isEqualTo(StatusDesligamento.NAO_SOLICITADO);
        assertThat(encontrada.getCliente().getId()).isEqualTo(cliente.getId());
    }
}
