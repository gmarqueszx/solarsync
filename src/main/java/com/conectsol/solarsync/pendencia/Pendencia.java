package com.conectsol.solarsync.pendencia;

import java.time.Instant;

import com.conectsol.solarsync.auth.Usuario;
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
@Table(name = "pendencia")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Pendencia extends BaseEntity {

    @NotNull
    @ManyToOne
    @JoinColumn(name = "cliente_id", nullable = false)
    private Cliente cliente;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 50)
    private TipoPendencia tipo;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private StatusPendencia status;

    @NotNull
    @Column(name = "solicitado_em", nullable = false)
    private Instant solicitadoEm;

    @Column(name = "resolvido_em")
    private Instant resolvidoEm;

    @ManyToOne
    @JoinColumn(name = "responsavel_id")
    private Usuario responsavel;

    @Column(name = "observacao", length = 1000)
    private String observacao;
}
