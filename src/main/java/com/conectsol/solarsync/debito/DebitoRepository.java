package com.conectsol.solarsync.debito;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DebitoRepository
        extends JpaRepository<Debito, Long>, JpaSpecificationExecutor<Debito> {

    Optional<Debito> findByClienteId(Long clienteId);

    boolean existsByClienteIdAndStatus(Long clienteId, StatusDebito status);
}
