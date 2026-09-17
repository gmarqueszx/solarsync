package com.conectsol.solarsync.cliente;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ClienteRepository
        extends JpaRepository<Cliente, Long>, JpaSpecificationExecutor<Cliente> {

    /** Tamanho da fila de triagem, para a tela mostrar o número sem paginar a lista toda. */
    long countByStatusTriagem(StatusTriagem statusTriagem);
}
