package com.conectsol.solarsync.vistoria;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface VistoriaRepository
        extends JpaRepository<Vistoria, Long>, JpaSpecificationExecutor<Vistoria> {

    List<Vistoria> findByProjetoId(Long projetoId);
}
