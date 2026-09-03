package com.conectsol.solarsync.debito;

import java.time.Instant;

import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "debito")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Debito extends BaseEntity {

    @NotNull
    @ManyToOne
    @JoinColumn(name = "cliente_id", nullable = false)
    private Cliente cliente;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatusDebito status;

    @Column(name = "ultima_consulta_em")
    private Instant ultimaConsultaEm;
}
