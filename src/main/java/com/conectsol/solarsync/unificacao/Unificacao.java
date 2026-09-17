package com.conectsol.solarsync.unificacao;

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

    /**
     * Ciclo de solicitar e aguardar retorno — ver {@link StatusDesligamento}. Só faz sentido
     * depois de {@code feita = true}: confere-se a unificação e, se houve, pede-se o
     * desligamento do medidor unificado.
     */
    @Builder.Default
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "desligamento_status", nullable = false, length = 20)
    private StatusDesligamento desligamentoStatus = StatusDesligamento.NAO_SOLICITADO;

    @Column(name = "desligamento_solicitado_em")
    private LocalDate desligamentoSolicitadoEm;

    @Column(name = "desligamento_concluido_em")
    private LocalDate desligamentoConcluidoEm;
}
