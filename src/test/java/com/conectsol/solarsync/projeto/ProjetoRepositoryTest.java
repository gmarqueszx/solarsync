package com.conectsol.solarsync.projeto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.conectsol.solarsync.common.RepositoryTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.conectsol.solarsync.cliente.Cliente;

@RepositoryTest
class ProjetoRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ProjetoRepository projetoRepository;

    @Test
    void persisteERecuperaProjetoComStatusInicialRecebido() {
        Cliente cliente = entityManager.persistFlushFind(Cliente.builder().nome("Cliente Projeto").build());

        Projeto projeto = Projeto.builder()
                .cliente(cliente)
                .tipoProjeto(TipoProjeto.PROJETO_INICIAL)
                .status(StatusProjeto.RECEBIDO)
                .dataRecebimento(LocalDate.now())
                .build();

        Projeto salvo = projetoRepository.save(projeto);
        entityManager.flush();
        entityManager.clear();

        Projeto encontrado = projetoRepository.findById(salvo.getId()).orElseThrow();
        assertThat(encontrado.getStatus()).isEqualTo(StatusProjeto.RECEBIDO);
        assertThat(encontrado.getTipoProjeto()).isEqualTo(TipoProjeto.PROJETO_INICIAL);
    }
}
