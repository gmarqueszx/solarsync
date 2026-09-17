package com.conectsol.solarsync.cliente;

import java.time.LocalDate;

import com.conectsol.solarsync.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /**
     * Resultado da checagem de pendência na Coelba. Fica no Cliente, e não numa entidade
     * própria, porque é o retrato da situação atual dele — mesma escolha feita para
     * {@code Debito} (um registro por cliente); o vai-e-vem fica em {@code historico_status}.
     * <p>
     * O {@code @Builder.Default} é o que garante o valor inicial: Lombok move o inicializador
     * para o builder, então quem construir por {@code new Cliente()} recebe null — só o
     * Hibernate faz isso, e logo em seguida preenche o campo com o valor da linha.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status_triagem", nullable = false, length = 30)
    @Builder.Default
    private StatusTriagem statusTriagem = StatusTriagem.AGUARDANDO_VERIFICACAO;
}
