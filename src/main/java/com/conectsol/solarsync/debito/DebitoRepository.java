package com.conectsol.solarsync.debito;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DebitoRepository extends JpaRepository<Debito, Long> {

    Optional<Debito> findByClienteId(Long clienteId);
}
