package com.conectsol.solarsync.integracao.gmail;

import java.time.Instant;

import com.conectsol.solarsync.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Um e-mail da Coelba já processado. Tem duas funções, e é a primeira que obriga a tabela a
 * existir:
 * <ol>
 *   <li><b>idempotência</b>: o job lista mensagens por consulta ({@code newer_than:7d}), então as
 *       mesmas voltam na execução seguinte. O único em {@code mensagem_id} é o que impede
 *       reaplicar o status. A alternativa — rotular ou marcar como lida no Gmail — exigiria
 *       escopo de escrita na caixa de e-mail;</li>
 *   <li><b>auditoria</b>: o status muda sozinho e não há tela, então tem de haver onde olhar para
 *       responder "por que este projeto foi reprovado ontem às 8h". Guarda inclusive o e-mail que
 *       o parser <b>não</b> entendeu, que é justamente o caso que não pode desaparecer em
 *       silêncio.</li>
 * </ol>
 * Não é escrita por endpoint nenhum — só pelo job, como o {@code historico_status} só é escrito
 * pelo listener de domínio.
 */
@Entity
@Table(name = "email_coelba")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailCoelba extends BaseEntity {

    /** Id da mensagem no Gmail. É a chave de idempotência (único na V13). */
    @NotBlank
    @Column(name = "mensagem_id", nullable = false, length = 100)
    private String mensagemId;

    /**
     * Quando o Gmail recebeu a mensagem — não quando o job rodou. É desta data que sai a data de
     * aprovação do projeto, pela mesma razão que o débito registra a data da consulta e não a da
     * digitação (seção 5): senão a métrica de tempo até aprovação mediria a agilidade do job.
     */
    @Column(name = "recebido_em")
    private Instant recebidoEm;

    /** É por ele que uma pessoa reconhece o e-mail ao conferir o que a integração fez. */
    @Column(name = "assunto", length = 500)
    private String assunto;

    /** O número que efetivamente casou com um projeto, quando casou. */
    @Column(name = "numero_solicitacao", length = 50)
    private String numeroSolicitacao;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "resultado", nullable = false, length = 30)
    private ResultadoProcessamento resultado;

    /**
     * Sem {@code @ManyToOne} nem FK, de propósito, pelo mesmo motivo de
     * {@code historico_status.entidade_id}: com FK, excluir um projeto passaria a falhar com 409
     * por causa da trilha da integração. Preservar a trilha vale mais que a integridade aqui.
     */
    @Column(name = "projeto_id")
    private Long projetoId;

    /** O que aconteceu, em texto, para quem for diagnosticar não precisar do log do servidor. */
    @Column(name = "detalhe", length = 1000)
    private String detalhe;
}
