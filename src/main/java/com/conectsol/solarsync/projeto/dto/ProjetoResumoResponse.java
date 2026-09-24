package com.conectsol.solarsync.projeto.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.projeto.StatusProjeto;
import com.conectsol.solarsync.projeto.TipoProjeto;

/** Projeção de listagem: sem motivo de reprova (até 1000 caracteres) nem cliente completo. */
public record ProjetoResumoResponse(
        Long id,
        Long clienteId,
        String clienteNome,
        /** Traz o cliente prioritário ao topo da fila; ver PrioridadePrimeiro. */
        boolean clientePrioritario,
        /** Projeto pago por financiamento: muda a etapa de destino no Nectar, não o fluxo aqui. */
        boolean clienteBanco,
        TipoProjeto tipoProjeto,
        StatusProjeto status,
        Long analistaResponsavelId,
        String analistaResponsavelNome,
        LocalDate dataRecebimento,
        LocalDate dataEncaminhado,
        /** Vai na listagem porque é coluna da tabela e chave de busca, não detalhe. */
        String numeroSolicitacao,
        LocalDate dataAprovacao,
        LocalDate dataInstalacao,
        BigDecimal potenciaKwp) {

    public static ProjetoResumoResponse de(Projeto projeto) {
        return new ProjetoResumoResponse(
                projeto.getId(),
                projeto.getCliente().getId(),
                projeto.getCliente().getNome(),
                projeto.getCliente().isPrioridade(),
                projeto.getCliente().isBanco(),
                projeto.getTipoProjeto(),
                projeto.getStatus(),
                projeto.getAnalistaResponsavel() == null ? null
                        : projeto.getAnalistaResponsavel().getId(),
                projeto.getAnalistaResponsavel() == null ? null
                        : projeto.getAnalistaResponsavel().getNome(),
                projeto.getDataRecebimento(),
                projeto.getDataEncaminhado(),
                projeto.getNumeroSolicitacao(),
                projeto.getDataAprovacao(),
                projeto.getDataInstalacao(),
                projeto.getPotenciaKwp());
    }
}
