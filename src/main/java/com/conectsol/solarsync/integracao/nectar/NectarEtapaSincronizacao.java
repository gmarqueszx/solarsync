package com.conectsol.solarsync.integracao.nectar;

import java.time.Instant;

import com.conectsol.solarsync.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Uma tentativa de pôr o cliente na etapa certa do Nectar. Irmã de {@code email_coelba}, e pelas
 * mesmas duas razões: a mudança acontece sozinha, e não há tela.
 * <p>
 * <b>Idempotência</b> — antes de chamar o CRM, o serviço olha o último resultado bem-sucedido
 * deste cliente. Se ele já foi posto naquela etapa, não há requisição nenhuma.
 * <p>
 * <b>Auditoria</b> — a movimentação no SolarSync acontece de qualquer jeito (a integração nunca
 * desfaz o status correto), então, quando o CRM recusa, a única pista de que ele ficou para trás é
 * esta linha. É também a fila do reprocessamento.
 * <p>
 * Como {@code historico_status} e {@code email_coelba}, não tem endpoint de escrita. E como
 * {@code email_coelba}, {@code cliente_id} não tem FK: com FK, excluir um cliente passaria a
 * falhar com 409 por causa da trilha de uma integração.
 */
@Entity
@Table(name = "nectar_etapa_sincronizacao")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NectarEtapaSincronizacao extends BaseEntity {

    @Column(name = "cliente_id", nullable = false)
    private Long clienteId;

    @Column(name = "oportunidade_id", length = 50)
    private String oportunidadeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "etapa_fluxo", nullable = false, length = 40)
    private EtapaDoFluxo etapaFluxo;

    @Column(name = "banco", nullable = false)
    @Builder.Default
    private boolean banco = false;

    @Column(name = "funil_id")
    private Long funilId;

    @Column(name = "etapa_id")
    private Long etapaId;

    @Column(name = "etapa_nome", length = 200)
    private String etapaNome;

    @Enumerated(EnumType.STRING)
    @Column(name = "resultado", nullable = false, length = 30)
    private ResultadoSincronizacao resultado;

    /** A mensagem da recusa do CRM, ou a explicação de por que nada foi feito. */
    @Column(name = "detalhe", length = 1000)
    private String detalhe;

    @Column(name = "ocorrido_em", nullable = false)
    private Instant ocorridoEm;
}
