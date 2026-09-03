package com.conectsol.solarsync.pendencia;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PendenciaRepository
        extends JpaRepository<Pendencia, Long>, JpaSpecificationExecutor<Pendencia> {

    List<Pendencia> findByClienteId(Long clienteId);
}
