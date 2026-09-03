package com.conectsol.solarsync.unificacao;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UnificacaoRepository extends JpaRepository<Unificacao, Long> {

    List<Unificacao> findByClienteId(Long clienteId);
}
