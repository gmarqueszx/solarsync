package com.conectsol.solarsync.projeto;

import java.math.BigDecimal;
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

    /**
     * Número que a Coelba devolve ao receber o projeto. É a chave que liga este registro ao
     * e-mail diário de status — sem ela a leitura automática do e-mail teria de casar por nome
     * de cliente, que é ambíguo. Sem UNIQUE: projeto reenviado pode receber outro número, e a
     * importação da planilha traz o campo irregular.
     */
    @Column(name = "numero_solicitacao", length = 50)
    private String numeroSolicitacao;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatusProjeto status;

    @Column(name = "motivo_reprova", length = 1000)
    private String motivoReprova;

    @Column(name = "data_aprovacao")
    private LocalDate dataAprovacao;

    /**
     * Quando a usina foi instalada. Entrada manual (a informação chega pelo grupo de
     * instalados), e não um status: o status acompanha a homologação na Coelba, a instalação é
     * evento de campo. É o marco de partida da vistoria — sem ela, solicitar vistoria é barrado.
     */
    @Column(name = "data_instalacao")
    private LocalDate dataInstalacao;

    /** Porte da usina. Permite ao gestor somar kWp homologado por período. */
    @Column(name = "potencia_kwp", precision = 8, scale = 2)
    private BigDecimal potenciaKwp;
}
