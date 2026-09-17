package com.conectsol.solarsync.historico;

import java.time.Instant;

import com.conectsol.solarsync.common.EntidadeTipo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "historico_status")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HistoricoStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "entidade_tipo", nullable = false, length = 30)
    private EntidadeTipo entidadeTipo;

    @NotNull
    @Column(name = "entidade_id", nullable = false)
    private Long entidadeId;

    @Column(name = "status_anterior", length = 30)
    private String statusAnterior;

    @NotNull
    @Column(name = "status_novo", nullable = false, length = 30)
    private String statusNovo;

    @NotNull
    @Column(name = "ocorrido_em", nullable = false)
    private Instant ocorridoEm;

    @Column(name = "usuario_id")
    private Long usuarioId;
}
