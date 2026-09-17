package com.conectsol.solarsync.vistoria;

import java.time.LocalDate;

import com.conectsol.solarsync.common.BaseEntity;
import com.conectsol.solarsync.projeto.Projeto;

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
@Table(name = "vistoria")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vistoria extends BaseEntity {

    @NotNull
    @ManyToOne
    @JoinColumn(name = "projeto_id", nullable = false)
    private Projeto projeto;

    @NotNull
    @Column(name = "data_solicitacao", nullable = false)
    private LocalDate dataSolicitacao;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatusVistoria status;

    @Column(name = "data_resultado")
    private LocalDate dataResultado;
}
