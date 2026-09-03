package com.conectsol.solarsync.vistoria;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VistoriaRepository extends JpaRepository<Vistoria, Long> {

    List<Vistoria> findByProjetoId(Long projetoId);
}
