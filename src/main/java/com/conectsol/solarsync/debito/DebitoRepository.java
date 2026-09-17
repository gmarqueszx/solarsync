package com.conectsol.solarsync.debito;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * O par {@code (cliente, tipo)} é a chave de negócio: cada consulta que um analista registra
 * responde a uma pergunta específica ("este cliente tem débito travando a pendência?" /
 * "...travando a homologação?"). Todo método de leitura pede o tipo de propósito — um débito de
 * homologação não pode travar uma pendência por engano.
 */
public interface DebitoRepository
        extends JpaRepository<Debito, Long>, JpaSpecificationExecutor<Debito> {

    Optional<Debito> findByClienteIdAndTipo(Long clienteId, TipoDebito tipo);

    /** Os dois tipos de um cliente, para a tela de detalhe mostrar a situação completa. */
    List<Debito> findByClienteId(Long clienteId);

    boolean existsByClienteIdAndTipoAndStatus(Long clienteId, TipoDebito tipo,
            StatusDebito status);

    /** Houve consulta deste tipo, seja qual for o resultado — diferente de "não tem débito". */
    boolean existsByClienteIdAndTipo(Long clienteId, TipoDebito tipo);
}
