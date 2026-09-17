package com.conectsol.solarsync.projeto;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProjetoRepository
        extends JpaRepository<Projeto, Long>, JpaSpecificationExecutor<Projeto> {

    List<Projeto> findByClienteId(Long clienteId);

    Optional<Projeto> findFirstByClienteIdOrderByCriadoEmDesc(Long clienteId);
}
