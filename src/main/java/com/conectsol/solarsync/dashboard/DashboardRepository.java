package com.conectsol.solarsync.dashboard;

import java.time.LocalDate;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;

/**
 * Agregações do dashboard em SQL nativo. Não é um {@code JpaRepository} porque estas consultas
 * cruzam entidades e não pertencem a nenhuma delas — e porque calcular média em Java exigiria
 * carregar todas as linhas para memória.
 * <p>
 * Todas as médias saem em <b>dias</b>, e devolvem {@code null} quando não há caso no período:
 * {@code AVG} de conjunto vazio é nulo, e é assim que o dashboard distingue "não houve" de
 * "levou zero dias".
 */
@Repository
@RequiredArgsConstructor
class DashboardRepository {

    private final EntityManager entityManager;

    /**
     * Recorte de período aplicado sobre a data de referência de cada métrica. O
     * {@code CAST(:param AS date)} é obrigatório: sem ele o Postgres não consegue inferir o tipo
     * do parâmetro quando ele vem nulo.
     */
    private static String noPeriodo(String coluna) {
        return """
                AND (CAST(:de AS date) IS NULL OR (%1$s)::date >= CAST(:de AS date))
                AND (CAST(:ate AS date) IS NULL OR (%1$s)::date <= CAST(:ate AS date))
                """.formatted(coluna);
    }

    /**
     * Do pagamento até a primeira vez que o cliente apareceu em alguma etapa do fluxo. É a
     * métrica que mostra cliente esquecido na gaveta — a dor que motivou o projeto.
     */
    Double mediaDiasSemNinguemMexerNoCliente(LocalDate de, LocalDate ate) {
        return media("""
                SELECT AVG(EXTRACT(EPOCH FROM (primeira.ts - c.data_pagamento::timestamptz))
                           / 86400.0)
                FROM cliente c
                JOIN (
                    SELECT cliente_id, MIN(criado_em) AS ts FROM (
                        SELECT cliente_id, criado_em FROM pendencia
                        UNION ALL SELECT cliente_id, criado_em FROM projeto
                        UNION ALL SELECT cliente_id, criado_em FROM debito
                    ) interacoes GROUP BY cliente_id
                ) primeira ON primeira.cliente_id = c.id
                WHERE c.data_pagamento IS NOT NULL
                """ + noPeriodo("c.data_pagamento"), de, ate);
    }

    Double mediaDiasResolucaoDePendencia(LocalDate de, LocalDate ate) {
        return media("""
                SELECT AVG(EXTRACT(EPOCH FROM (resolvido_em - solicitado_em)) / 86400.0)
                FROM pendencia
                WHERE resolvido_em IS NOT NULL
                """ + noPeriodo("resolvido_em"), de, ate);
    }

    Double mediaDiasRecebimentoAteEnvio(LocalDate de, LocalDate ate) {
        return media("""
                SELECT AVG(data_encaminhado - data_recebimento)
                FROM projeto
                WHERE data_encaminhado IS NOT NULL AND data_recebimento IS NOT NULL
                """ + noPeriodo("data_encaminhado"), de, ate);
    }

    Double mediaDiasEnvioAteAprovacao(LocalDate de, LocalDate ate) {
        return media("""
                SELECT AVG(data_aprovacao - data_encaminhado)
                FROM projeto
                WHERE data_aprovacao IS NOT NULL AND data_encaminhado IS NOT NULL
                """ + noPeriodo("data_aprovacao"), de, ate);
    }

    /**
     * Da primeira detecção do débito até a primeira quitação, lido do histórico — é por isso
     * que {@code DebitoService} publica evento em vez de só gravar o status.
     */
    Double mediaDiasParadoPorDebito(LocalDate de, LocalDate ate) {
        return media("""
                SELECT AVG(EXTRACT(EPOCH FROM (quitado.primeiro - ativo.primeiro)) / 86400.0)
                FROM (SELECT entidade_id, MIN(ocorrido_em) AS primeiro
                      FROM historico_status
                      WHERE entidade_tipo = 'DEBITO' AND status_novo = 'ATIVO'
                      GROUP BY entidade_id) ativo
                JOIN (SELECT entidade_id, MIN(ocorrido_em) AS primeiro
                      FROM historico_status
                      WHERE entidade_tipo = 'DEBITO' AND status_novo = 'QUITADO'
                      GROUP BY entidade_id) quitado
                  ON quitado.entidade_id = ativo.entidade_id
                 AND quitado.primeiro > ativo.primeiro
                WHERE 1 = 1
                """ + noPeriodo("quitado.primeiro"), de, ate);
    }

    Double mediaDiasInstalacaoAteSolicitarVistoria(LocalDate de, LocalDate ate) {
        return media("""
                SELECT AVG(v.data_solicitacao - p.data_instalacao)
                FROM vistoria v
                JOIN projeto p ON p.id = v.projeto_id
                WHERE p.data_instalacao IS NOT NULL
                """ + noPeriodo("v.data_solicitacao"), de, ate);
    }

    /** Recebimento do projeto até a vistoria aprovada: o ciclo inteiro, ponta a ponta. */
    Double mediaDiasCicloCompleto(LocalDate de, LocalDate ate) {
        return media("""
                SELECT AVG(v.data_resultado - p.data_recebimento)
                FROM vistoria v
                JOIN projeto p ON p.id = v.projeto_id
                WHERE v.status = 'APROVADA'
                  AND v.data_resultado IS NOT NULL
                  AND p.data_recebimento IS NOT NULL
                """ + noPeriodo("v.data_resultado"), de, ate);
    }

    long pendenciasResolvidas(LocalDate de, LocalDate ate) {
        return contar("""
                SELECT COUNT(*) FROM pendencia
                WHERE status = 'RESOLVIDA' AND resolvido_em IS NOT NULL
                """ + noPeriodo("resolvido_em"), de, ate);
    }

    /**
     * Conta por {@code data_encaminhado}, não pelo status atual: um projeto enviado e já
     * aprovado continua tendo sido enviado no período, e o gestor quer saber o volume de envio.
     */
    long projetosEncaminhados(LocalDate de, LocalDate ate) {
        return contar("""
                SELECT COUNT(*) FROM projeto
                WHERE data_encaminhado IS NOT NULL
                """ + noPeriodo("data_encaminhado"), de, ate);
    }

    long projetosAprovados(LocalDate de, LocalDate ate) {
        return contar("""
                SELECT COUNT(*) FROM projeto
                WHERE data_aprovacao IS NOT NULL
                """ + noPeriodo("data_aprovacao"), de, ate);
    }

    /**
     * Não existe coluna de data de reprova, então o período vem do histórico. Conta projetos
     * distintos: reprovado duas vezes conta uma, para bater com "projetos reprovados".
     */
    long projetosReprovados(LocalDate de, LocalDate ate) {
        return contar("""
                SELECT COUNT(DISTINCT entidade_id) FROM historico_status
                WHERE entidade_tipo = 'PROJETO' AND status_novo = 'REPROVADO'
                """ + noPeriodo("ocorrido_em"), de, ate);
    }

    /**
     * Situação de agora, não do período: "quantos clientes estão travados neste momento". Um
     * recorte de datas aqui responderia outra pergunta, e menos útil.
     */
    long clientesComDebitoAtivo() {
        Query consulta = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM debito WHERE status = 'ATIVO'");
        return ((Number) consulta.getSingleResult()).longValue();
    }

    long vistoriasSolicitadas(LocalDate de, LocalDate ate) {
        return contar("""
                SELECT COUNT(*) FROM vistoria WHERE 1 = 1
                """ + noPeriodo("data_solicitacao"), de, ate);
    }

    private Double media(String sql, LocalDate de, LocalDate ate) {
        Number resultado = (Number) comPeriodo(sql, de, ate).getSingleResult();
        return resultado == null ? null : resultado.doubleValue();
    }

    private long contar(String sql, LocalDate de, LocalDate ate) {
        return ((Number) comPeriodo(sql, de, ate).getSingleResult()).longValue();
    }

    private Query comPeriodo(String sql, LocalDate de, LocalDate ate) {
        return entityManager.createNativeQuery(sql)
                .setParameter("de", de)
                .setParameter("ate", ate);
    }
}
