package com.conectsol.solarsync.pendencia;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PendenciaRepository extends JpaRepository<Pendencia, Long> {

    List<Pendencia> findByClienteId(Long clienteId);
}
