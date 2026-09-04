package com.conectsol.solarsync.cliente;

import java.time.LocalDate;

import com.conectsol.solarsync.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "cliente")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cliente extends BaseEntity {

    @NotBlank
    @Column(name = "nome", nullable = false, length = 150)
    private String nome;

    @Column(name = "cidade", length = 100)
    private String cidade;

    @Column(name = "vendedor", length = 150)
    private String vendedor;

    @Column(name = "data_pagamento")
    private LocalDate dataPagamento;

    /**
     * Unidade Consumidora da Coelba. Não é única: um cliente pode ter mais de uma UC — é disso
     * que trata a etapa de unificação. Aqui fica a principal.
     */
    @Column(name = "uc_coelba", length = 30)
    private String ucCoelba;

    @Column(name = "telefone", length = 20)
    private String telefone;
}
