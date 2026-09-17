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
                .tipo(TipoDebito.HOMOLOGACAO)
                .status(StatusDebito.ATIVO)
                .ultimaConsultaEm(Instant.now())
                .detectadoEm(Instant.now())
                .build();

        debitoRepository.save(debito);
        entityManager.flush();
        entityManager.clear();

        Debito encontrado = debitoRepository
                .findByClienteIdAndTipo(cliente.getId(), TipoDebito.HOMOLOGACAO).orElseThrow();
        assertThat(encontrado.getStatus()).isEqualTo(StatusDebito.ATIVO);
    }

    @Test
    void rejeitaDoisDebitosDoMesmoTipoParaOMesmoCliente() {
        Cliente cliente = novoCliente();
        debitoRepository.save(Debito.builder().cliente(cliente).tipo(TipoDebito.PENDENCIA)
                .status(StatusDebito.ATIVO).build());
        entityManager.flush();

        assertThatThrownBy(() -> {
            debitoRepository.save(Debito.builder().cliente(cliente).tipo(TipoDebito.PENDENCIA)
                    .status(StatusDebito.QUITADO).build());
            entityManager.flush();
        }).isInstanceOf(DataAccessException.class);
    }

    /**
     * Os dois tipos convivem: o débito que trava a pendência e o que trava a homologação são
     * consultas diferentes, e o cliente pode estar quitado numa e devendo na outra. É esta
     * coexistência que a tela de débitos precisa mostrar.
     */
    @Test
    void aceitaOsDoisTiposParaOMesmoCliente() {
        Cliente cliente = novoCliente();

        debitoRepository.save(Debito.builder().cliente(cliente).tipo(TipoDebito.PENDENCIA)
                .status(StatusDebito.QUITADO).build());
        debitoRepository.save(Debito.builder().cliente(cliente).tipo(TipoDebito.HOMOLOGACAO)
                .status(StatusDebito.ATIVO).build());
        entityManager.flush();
        entityManager.clear();

        assertThat(debitoRepository.findByClienteId(cliente.getId())).hasSize(2);
        assertThat(debitoRepository.existsByClienteIdAndTipoAndStatus(
                cliente.getId(), TipoDebito.HOMOLOGACAO, StatusDebito.ATIVO)).isTrue();
        assertThat(debitoRepository.existsByClienteIdAndTipoAndStatus(
                cliente.getId(), TipoDebito.PENDENCIA, StatusDebito.ATIVO)).isFalse();
    }
}
