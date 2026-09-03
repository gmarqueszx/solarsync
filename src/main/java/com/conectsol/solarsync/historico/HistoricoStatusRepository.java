package com.conectsol.solarsync.historico;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.conectsol.solarsync.common.EntidadeTipo;

public interface HistoricoStatusRepository extends JpaRepository<HistoricoStatus, Long> {

    List<HistoricoStatus> findByEntidadeTipoAndEntidadeId(EntidadeTipo entidadeTipo, Long entidadeId);
}
