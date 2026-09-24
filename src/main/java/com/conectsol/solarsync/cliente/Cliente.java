package com.conectsol.solarsync.cliente;

import java.time.Instant;
import java.time.LocalDate;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
     * Id da oportunidade no Nectar, quando o cadastro veio da sincronização com o CRM em vez da
     * tela. É a chave de idempotência da sincronização (índice único na V13): o mesmo negócio
     * fechado nunca gera dois clientes, ainda que o job releia a mesma página do CRM.
     * <p>
     * É também a procedência do registro. Sem ela, um cliente que ninguém digitou apareceria na
     * fila da triagem sem explicação — e a criação do cliente não publica evento, então nem o
     * historico_status contaria de onde ele veio.
     */
    @Column(name = "nectar_oportunidade_id", length = 50)
    private String nectarOportunidadeId;

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

    /**
     * Cliente adiantado a pedido. O efeito é de <b>ordenação</b>: ele sobe ao topo da fila da
     * etapa em que estiver, e continua subindo nas etapas seguintes — por isso mora no cliente e
     * não em nenhuma das entidades de etapa, que nascem e morrem ao longo do fluxo.
     */
    @Column(name = "prioridade", nullable = false)
    @Builder.Default
    private boolean prioridade = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "prioridade_motivo", length = 30)
    private MotivoPrioridade prioridadeMotivo;

    @Column(name = "prioridade_observacao", length = 500)
    private String prioridadeObservacao;

    @Column(name = "prioridade_definida_em")
    private Instant prioridadeDefinidaEm;

    /**
     * Quem pediu a prioridade. É a pergunta que vem junto com "isto ainda vale?", e não há como
     * respondê-la depois se não ficar registrada na hora.
     */
    @ManyToOne
    @JoinColumn(name = "prioridade_definida_por_id")
    private Usuario prioridadeDefinidaPor;

    /**
     * Data em que a usina foi instalada, quando a prioridade é por instalação adiantada.
     * <p>
     * ⚠️ <b>Não é um segundo campo de data de instalação.</b> O campo do fluxo continua sendo
     * {@code projeto.data_instalacao}, que é o que a vistoria lê e exige. Este aqui existe porque
     * a prioridade é marcada <b>na triagem</b>, quando muitas vezes ainda não há projeto nenhum
     * para receber a data — ele é o lugar onde ela espera. Assim que o projeto existe (na hora,
     * se já existir; no nascimento dele, se não) o valor é copiado para lá, e a partir daí quem
     * manda é o projeto.
     */
    @Column(name = "prioridade_data_instalacao")
    private LocalDate prioridadeDataInstalacao;

    /**
     * Cliente avulso que o gestor manda ao setor <b>só</b> para resolver uma pendência
     * específica: entrada → pendência → pendência resolvida → fim. Resolvida a pendência, nenhum
     * projeto nasce (a guarda está em {@code ProjetoService}, não nos listeners, para valer
     * qualquer que seja a origem).
     * <p>
     * Flag e não status: não é uma etapa do processo, é o desenho do fluxo daquele cliente,
     * decidido na entrada e válido do começo ao fim.
     */
    @Column(name = "somente_pendencia", nullable = false)
    @Builder.Default
    private boolean somentePendencia = false;

    /**
     * Projeto pago por financiamento bancário. Muda uma coisa só, e fora do SolarSync: a etapa
     * para onde o cliente vai no Nectar quando o projeto é aprovado. Dentro daqui o fluxo é
     * idêntico ao normal.
     * <p>
     * Coluna, e não dedução da etapa de origem no CRM: a etapa de origem não sobrevive à
     * importação, e um cliente Banco cadastrado à mão não teria como ser marcado.
     */
    @Column(name = "banco", nullable = false)
    @Builder.Default
    private boolean banco = false;
}
