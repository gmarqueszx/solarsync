package com.conectsol.solarsync.debito;

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

    /**
     * Que etapa este débito trava. Junto com o cliente, é a identidade do registro: há no
     * máximo um débito por cliente <b>por tipo</b>.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 20)
    private TipoDebito tipo;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatusDebito status;

    @Column(name = "ultima_consulta_em")
    private Instant ultimaConsultaEm;

    /**
     * Quando o débito foi constatado. É o começo do relógio que a tela de Débitos mostra como
     * "parado há N dias" — o número que faz o financeiro entrar em ação.
     * <p>
     * A média do dashboard continua saindo do {@code historico_status}; esta coluna existe
     * porque a listagem precisa do tempo linha a linha, e varrer o histórico por linha seria
     * caro. Ela é escrita só pelo {@code DebitoService}, junto com a mudança de status.
     */
    @Column(name = "detectado_em")
    private Instant detectadoEm;

    /** Preenchido na quitação; {@code detectadoEm} é mantido, para o par sobreviver na tela. */
    @Column(name = "quitado_em")
    private Instant quitadoEm;

    @ManyToOne
    @JoinColumn(name = "consultado_por_id")
    private Usuario consultadoPor;
}
