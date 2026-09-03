package com.conectsol.solarsync.projeto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

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
                .tipoProjeto(TipoProjeto.PADRAO)
                .status(StatusProjeto.RECEBIDO)
                .dataRecebimento(LocalDate.now())
                .build();

        Projeto salvo = projetoRepository.save(projeto);
        entityManager.flush();
        entityManager.clear();

        Projeto encontrado = projetoRepository.findById(salvo.getId()).orElseThrow();
        assertThat(encontrado.getStatus()).isEqualTo(StatusProjeto.RECEBIDO);
        assertThat(encontrado.getTipoProjeto()).isEqualTo(TipoProjeto.PADRAO);
    }
}
