package com.conectsol.solarsync.unificacao;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UnificacaoRepository
        extends JpaRepository<Unificacao, Long>, JpaSpecificationExecutor<Unificacao> {

    List<Unificacao> findByClienteId(Long clienteId);
}
