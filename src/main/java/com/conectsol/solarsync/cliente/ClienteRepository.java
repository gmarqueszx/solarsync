package com.conectsol.solarsync.cliente;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ClienteRepository
        extends JpaRepository<Cliente, Long>, JpaSpecificationExecutor<Cliente> {

    /** Tamanho da fila de triagem, para a tela mostrar o número sem paginar a lista toda. */
    long countByStatusTriagem(StatusTriagem statusTriagem);

    /**
     * Idempotência da sincronização com o Nectar: o job relê a mesma página do CRM a cada
     * execução, então "já criei cliente para este negócio" precisa ser uma pergunta barata.
     * O índice único da V13 é a garantia de verdade; isto só evita o insert condenado.
     */
    boolean existsByNectarOportunidadeId(String nectarOportunidadeId);
}
