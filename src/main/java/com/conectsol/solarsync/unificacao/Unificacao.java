package com.conectsol.solarsync.unificacao;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "unificacao")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Unificacao extends BaseEntity {

    @NotNull
    @ManyToOne
    @JoinColumn(name = "cliente_id", nullable = false)
    private Cliente cliente;

    @Column(name = "cidade", length = 100)
    private String cidade;

    @ManyToOne
    @JoinColumn(name = "projetista_id")
    private Usuario projetista;

    @Column(name = "informacoes", length = 1000)
    private String informacoes;

    @Builder.Default
    @Column(name = "feita", nullable = false)
    private boolean feita = false;

    @Builder.Default
    @Column(name = "desligamento", nullable = false)
    private boolean desligamento = false;
}
