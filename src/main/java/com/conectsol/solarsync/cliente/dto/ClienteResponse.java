package com.conectsol.solarsync.cliente.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.conectsol.solarsync.auth.dto.UsuarioResumoResponse;
import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.EtiquetaCliente;
import com.conectsol.solarsync.cliente.MotivoPrioridade;
import com.conectsol.solarsync.cliente.OrigemCliente;
import com.conectsol.solarsync.cliente.StatusTriagem;

/**
 * @param statusTriagem            resultado da checagem de pendência na Coelba. É o que permite
 *                                 ao frontend montar a fila "falta checar" em vez de deduzi-la da
 *                                 ausência de registros
 * @param origem                   {@code MANUAL} ou {@code CRM_NECTAR}. A triagem mostra isso
 *                                 porque a confiança nos dados é diferente: no cliente do CRM,
 *                                 campo vazio significa "o CRM não sabia" e pede preenchimento;
 *                                 no manual, é esquecimento. Campo próprio em vez de o frontend
 *                                 deduzir de {@code nectarOportunidadeId != null} — a regra fica
 *                                 num lugar só
 * @param nectarOportunidadeId     o id da oportunidade no Nectar, quando veio de lá. Serve para
 *                                 achar o negócio no CRM a partir do cliente
 * @param etiquetas                os selos do cliente, derivados de {@code origem} e
 *                                 {@code banco}. Derivados e não armazenados: é o que impede,
 *                                 por construção, a etiqueta repetida quando a importação roda de
 *                                 novo
 * @param prioridade               o cliente foi adiantado a pedido; as listagens o trazem no topo
 *                                 da etapa em que estiver
 * @param prioridadeDataInstalacao quando a prioridade é por instalação adiantada, a data que
 *                                 desce para {@code projeto.data_instalacao}. Continua exposta
 *                                 aqui para a tela mostrar o motivo por extenso, mas quem manda
 *                                 na etapa de vistoria é o campo do projeto
 * @param somentePendencia         fluxo curto: entrada → pendência → resolvida → fim, sem projeto
 * @param banco                    projeto pago por financiamento bancário
 */
public record ClienteResponse(
        Long id,
        String nome,
        String cidade,
        String vendedor,
        LocalDate dataPagamento,
        String ucCoelba,
        String telefone,
        StatusTriagem statusTriagem,
        OrigemCliente origem,
        String nectarOportunidadeId,
        List<EtiquetaCliente> etiquetas,
        boolean prioridade,
        MotivoPrioridade prioridadeMotivo,
        String prioridadeObservacao,
        Instant prioridadeDefinidaEm,
        UsuarioResumoResponse prioridadeDefinidaPor,
        LocalDate prioridadeDataInstalacao,
        boolean somentePendencia,
        boolean banco) {

    public static ClienteResponse de(Cliente cliente) {
        return new ClienteResponse(
                cliente.getId(),
                cliente.getNome(),
                cliente.getCidade(),
                cliente.getVendedor(),
                cliente.getDataPagamento(),
                cliente.getUcCoelba(),
                cliente.getTelefone(),
                cliente.getStatusTriagem(),
                OrigemCliente.de(cliente),
                cliente.getNectarOportunidadeId(),
                EtiquetaCliente.de(cliente),
                cliente.isPrioridade(),
                cliente.getPrioridadeMotivo(),
                cliente.getPrioridadeObservacao(),
                cliente.getPrioridadeDefinidaEm(),
                UsuarioResumoResponse.de(cliente.getPrioridadeDefinidaPor()),
                cliente.getPrioridadeDataInstalacao(),
                cliente.isSomentePendencia(),
                cliente.isBanco());
    }
}
