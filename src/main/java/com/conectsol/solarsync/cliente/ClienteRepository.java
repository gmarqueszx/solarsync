package com.conectsol.solarsync.cliente;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClienteRepository extends JpaRepository<Cliente, Long> {

    /** Busca única da tela: o analista digita nome ou número de UC no mesmo campo. */
    Page<Cliente> findByNomeContainingIgnoreCaseOrUcCoelbaContainingIgnoreCase(
            String nome, String ucCoelba, Pageable paginacao);
}
