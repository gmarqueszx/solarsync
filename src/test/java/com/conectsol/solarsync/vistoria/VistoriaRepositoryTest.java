package com.conectsol.solarsync.vistoria;

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
import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.projeto.StatusProjeto;
import com.conectsol.solarsync.projeto.TipoProjeto;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class VistoriaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private VistoriaRepository vistoriaRepository;

    @Test
    void persisteERecuperaVistoria() {
        Cliente cliente = entityManager.persistFlushFind(Cliente.builder().nome("Cliente Vistoria").build());
        Projeto projeto = entityManager.persistFlushFind(Projeto.builder()
                .cliente(cliente)
                .tipoProjeto(TipoProjeto.PADRAO)
                .status(StatusProjeto.APROVADO)
                .build());

        Vistoria vistoria = Vistoria.builder()
                .projeto(projeto)
                .dataSolicitacao(LocalDate.now())
                .status(StatusVistoria.SOLICITADA)
                .build();

        Vistoria salva = vistoriaRepository.save(vistoria);
        entityManager.flush();
        entityManager.clear();

        Vistoria encontrada = vistoriaRepository.findById(salva.getId()).orElseThrow();
        assertThat(encontrada.getStatus()).isEqualTo(StatusVistoria.SOLICITADA);
        assertThat(encontrada.getProjeto().getId()).isEqualTo(projeto.getId());
    }
}
