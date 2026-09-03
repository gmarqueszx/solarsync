package com.conectsol.solarsync.projeto;

import java.time.LocalDate;

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
@Table(name = "projeto")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Projeto extends BaseEntity {

    @NotNull
    @ManyToOne
    @JoinColumn(name = "cliente_id", nullable = false)
    private Cliente cliente;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_projeto", nullable = false, length = 40)
    private TipoProjeto tipoProjeto;

    @ManyToOne
    @JoinColumn(name = "analista_responsavel_id")
    private Usuario analistaResponsavel;

    @Column(name = "data_recebimento")
    private LocalDate dataRecebimento;

    @Column(name = "data_art")
    private LocalDate dataArt;

    @Column(name = "data_encaminhado")
    private LocalDate dataEncaminhado;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatusProjeto status;

    @Column(name = "motivo_reprova", length = 1000)
    private String motivoReprova;

    @Column(name = "data_aprovacao")
    private LocalDate dataAprovacao;
}
