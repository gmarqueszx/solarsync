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
     * Recorte por analista, aplicado sobre a coluna de responsável de cada entidade — que tem
     * nome diferente em cada uma ({@code responsavel_id} na pendência,
     * {@code analista_responsavel_id} no projeto, {@code consultado_por_id} no débito,
     * {@code projetista_id} na unificação), porque cada etapa tem um dono diferente.
     * <p>
     * Nulo é a equipe inteira, e é o padrão. Linha sem responsável <b>sai</b> do recorte quando
     * um analista é escolhido: "o que passou pelas mãos da Larissa" não inclui o que não passou
     * pelas mãos de ninguém — esse é o buraco "projeto sem analista", que tem lugar próprio.
     */
    private static String doAnalista(String coluna) {
        return """
                AND (CAST(:analistaId AS bigint) IS NULL
                     OR %s = CAST(:analistaId AS bigint))
                """.formatted(coluna);
    }

    /**
     * Do pagamento até a primeira vez que o cliente apareceu em alguma etapa do fluxo. É a
     * métrica que mostra cliente esquecido na gaveta — a dor que motivou o projeto.
     * <p>
     * O {@code DISTINCT ON} existe por causa do recorte por analista: além do instante da
     * primeira interação, ele precisa de <i>quem</i> a fez, e um {@code MIN(criado_em)} agrupado
     * devolve a data sem a pessoa.
     */
    Double mediaDiasSemNinguemMexerNoCliente(LocalDate de, LocalDate ate, Long analistaId) {
        return media("""
                SELECT AVG(EXTRACT(EPOCH FROM (primeira.ts - c.data_pagamento::timestamptz))
                           / 86400.0)
                FROM cliente c
                JOIN (
                    SELECT DISTINCT ON (cliente_id)
                           cliente_id, criado_em AS ts, responsavel_id
                    FROM (
                        SELECT cliente_id, criado_em, responsavel_id FROM pendencia
                        UNION ALL
                        SELECT cliente_id, criado_em, analista_responsavel_id FROM projeto
                        UNION ALL
                        SELECT cliente_id, criado_em, consultado_por_id FROM debito
                    ) interacoes
                    ORDER BY cliente_id, criado_em
                ) primeira ON primeira.cliente_id = c.id
                WHERE c.data_pagamento IS NOT NULL
                """ + noPeriodo("c.data_pagamento") + doAnalista("primeira.responsavel_id"),
                de, ate, analistaId);
    }

    Double mediaDiasResolucaoDePendencia(LocalDate de, LocalDate ate, Long analistaId) {
        return media("""
                SELECT AVG(EXTRACT(EPOCH FROM (resolvido_em - solicitado_em)) / 86400.0)
                FROM pendencia
                WHERE resolvido_em IS NOT NULL
                """ + noPeriodo("resolvido_em") + doAnalista("responsavel_id"),
                de, ate, analistaId);
    }

    Double mediaDiasRecebimentoAteEnvio(LocalDate de, LocalDate ate, Long analistaId) {
        return media("""
                SELECT AVG(data_encaminhado - data_recebimento)
                FROM projeto
                WHERE data_encaminhado IS NOT NULL AND data_recebimento IS NOT NULL
                """ + noPeriodo("data_encaminhado") + doAnalista("analista_responsavel_id"),
                de, ate, analistaId);
    }

    Double mediaDiasEnvioAteAprovacao(LocalDate de, LocalDate ate, Long analistaId) {
        return media("""
                SELECT AVG(data_aprovacao - data_encaminhado)
                FROM projeto
                WHERE data_aprovacao IS NOT NULL AND data_encaminhado IS NOT NULL
                """ + noPeriodo("data_aprovacao") + doAnalista("analista_responsavel_id"),
                de, ate, analistaId);
    }

    /**
     * Da primeira detecção do débito até a primeira quitação, lido do histórico — é por isso
     * que {@code DebitoService} publica evento em vez de só gravar o status.
     * <p>
     * O {@code LEFT JOIN debito} existe só para o recorte por analista, e é LEFT de propósito:
     * com filtro nulo, um débito excluído continua contando como contava antes.
     */
    Double mediaDiasParadoPorDebito(LocalDate de, LocalDate ate, Long analistaId) {
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
                LEFT JOIN debito d ON d.id = ativo.entidade_id
                WHERE 1 = 1
                """ + noPeriodo("quitado.primeiro") + doAnalista("d.consultado_por_id"),
                de, ate, analistaId);
    }

    Double mediaDiasInstalacaoAteSolicitarVistoria(LocalDate de, LocalDate ate, Long analistaId) {
        return media("""
                SELECT AVG(v.data_solicitacao - p.data_instalacao)
                FROM vistoria v
                JOIN projeto p ON p.id = v.projeto_id
                WHERE p.data_instalacao IS NOT NULL
                """ + noPeriodo("v.data_solicitacao") + doAnalista("p.analista_responsavel_id"),
                de, ate, analistaId);
    }

    /** Recebimento do projeto até a vistoria aprovada: o ciclo inteiro, ponta a ponta. */
    Double mediaDiasCicloCompleto(LocalDate de, LocalDate ate, Long analistaId) {
        return media("""
                SELECT AVG(v.data_resultado - p.data_recebimento)
                FROM vistoria v
                JOIN projeto p ON p.id = v.projeto_id
                WHERE v.status = 'APROVADA'
                  AND v.data_resultado IS NOT NULL
                  AND p.data_recebimento IS NOT NULL
                """ + noPeriodo("v.data_resultado") + doAnalista("p.analista_responsavel_id"),
                de, ate, analistaId);
    }

    long pendenciasResolvidas(LocalDate de, LocalDate ate, Long analistaId) {
        return contar("""
                SELECT COUNT(*) FROM pendencia
                WHERE status = 'RESOLVIDA' AND resolvido_em IS NOT NULL
                """ + noPeriodo("resolvido_em") + doAnalista("responsavel_id"),
                de, ate, analistaId);
    }

    /**
     * Conta por {@code data_encaminhado}, não pelo status atual: um projeto enviado e já
     * aprovado continua tendo sido enviado no período, e o gestor quer saber o volume de envio.
     */
    long projetosEncaminhados(LocalDate de, LocalDate ate, Long analistaId) {
        return contar("""
                SELECT COUNT(*) FROM projeto
                WHERE data_encaminhado IS NOT NULL
                """ + noPeriodo("data_encaminhado") + doAnalista("analista_responsavel_id"),
                de, ate, analistaId);
    }

    long projetosAprovados(LocalDate de, LocalDate ate, Long analistaId) {
        return contar("""
                SELECT COUNT(*) FROM projeto
                WHERE data_aprovacao IS NOT NULL
                """ + noPeriodo("data_aprovacao") + doAnalista("analista_responsavel_id"),
                de, ate, analistaId);
    }

    /**
     * Não existe coluna de data de reprova, então o período vem do histórico. Conta projetos
     * distintos: reprovado duas vezes conta uma, para bater com "projetos reprovados".
     * <p>
     * O join com projeto é LEFT porque {@code historico_status} não tem FK para a entidade: um
     * projeto excluído deixa histórico órfão, e com filtro nulo ele continua contando como
     * contava antes.
     */
    long projetosReprovados(LocalDate de, LocalDate ate, Long analistaId) {
        return contar("""
                SELECT COUNT(DISTINCT h.entidade_id) FROM historico_status h
                LEFT JOIN projeto p ON p.id = h.entidade_id
                WHERE h.entidade_tipo = 'PROJETO' AND h.status_novo = 'REPROVADO'
                """ + noPeriodo("h.ocorrido_em") + doAnalista("p.analista_responsavel_id"),
                de, ate, analistaId);
    }

    /**
     * Situação de agora, não do período: "quantos clientes estão travados neste momento". Um
     * recorte de datas aqui responderia outra pergunta, e menos útil.
     * <p>
     * {@code DISTINCT cliente_id} porque há uma linha de débito por tipo: um cliente travado nas
     * duas etapas contaria duas vezes, e a métrica se chama <b>clientes</b>.
     */
    long clientesComDebitoAtivo(Long analistaId) {
        return semPeriodo("SELECT COUNT(DISTINCT cliente_id) FROM debito WHERE status = 'ATIVO'\n"
                + doAnalista("consultado_por_id"), analistaId);
    }

    /** Quem está parado antes mesmo de a pendência poder ser resolvida. */
    long clientesTravadosNaPendencia(Long analistaId) {
        return semPeriodo("SELECT COUNT(DISTINCT cliente_id) FROM debito "
                + "WHERE status = 'ATIVO' AND tipo = 'PENDENCIA'\n"
                + doAnalista("consultado_por_id"), analistaId);
    }

    /** Quem está com o projeto pronto e o envio à Coelba bloqueado. */
    long clientesTravadosNaHomologacao(Long analistaId) {
        return semPeriodo("SELECT COUNT(DISTINCT cliente_id) FROM debito "
                + "WHERE status = 'ATIVO' AND tipo = 'HOMOLOGACAO'\n"
                + doAnalista("consultado_por_id"), analistaId);
    }

    private long semPeriodo(String sql, Long analistaId) {
        Query consulta = entityManager.createNativeQuery(sql)
                .setParameter("analistaId", analistaId);
        return ((Number) consulta.getSingleResult()).longValue();
    }

    long pendenciasAbertasNoPeriodo(LocalDate de, LocalDate ate, Long analistaId) {
        return contar("""
                SELECT COUNT(*) FROM pendencia WHERE 1 = 1
                """ + noPeriodo("solicitado_em") + doAnalista("responsavel_id"),
                de, ate, analistaId);
    }

    /** Como reprovados: sai do histórico, porque não há coluna de data de reencaminhamento. */
    long projetosReencaminhados(LocalDate de, LocalDate ate, Long analistaId) {
        return contar("""
                SELECT COUNT(DISTINCT h.entidade_id) FROM historico_status h
                LEFT JOIN projeto p ON p.id = h.entidade_id
                WHERE h.entidade_tipo = 'PROJETO' AND h.status_novo = 'REENCAMINHADO'
                """ + noPeriodo("h.ocorrido_em") + doAnalista("p.analista_responsavel_id"),
                de, ate, analistaId);
    }

    long clientesComDebitoQuitado(Long analistaId) {
        return semPeriodo(
                "SELECT COUNT(DISTINCT cliente_id) FROM debito WHERE status = 'QUITADO'\n"
                        + doAnalista("consultado_por_id"), analistaId);
    }

    long unificacoesPendentes(Long analistaId) {
        return semPeriodo("SELECT COUNT(*) FROM unificacao WHERE feita = FALSE\n"
                + doAnalista("projetista_id"), analistaId);
    }

    /** A fila que mais se perde de vista: pedido feito, ninguém voltou a olhar. */
    long desligamentosAguardando(Long analistaId) {
        return semPeriodo(
                "SELECT COUNT(*) FROM unificacao WHERE desligamento_status = 'SOLICITADO'\n"
                        + doAnalista("projetista_id"), analistaId);
    }

    long desligamentosComOsAberta(Long analistaId) {
        return semPeriodo(
                "SELECT COUNT(*) FROM unificacao WHERE desligamento_status = 'OS_ABERTA'\n"
                        + doAnalista("projetista_id"), analistaId);
    }

    long desligamentosConcluidos(LocalDate de, LocalDate ate, Long analistaId) {
        return contar("""
                SELECT COUNT(*) FROM unificacao
                WHERE desligamento_status = 'CONCLUIDO' AND desligamento_concluido_em IS NOT NULL
                """ + noPeriodo("desligamento_concluido_em") + doAnalista("projetista_id"),
                de, ate, analistaId);
    }

    /** Da solicitação até o medidor efetivamente desligado. */
    Double mediaDiasEsperaDoDesligamento(LocalDate de, LocalDate ate, Long analistaId) {
        return media("""
                SELECT AVG(desligamento_concluido_em - desligamento_solicitado_em)
                FROM unificacao
                WHERE desligamento_concluido_em IS NOT NULL
                  AND desligamento_solicitado_em IS NOT NULL
                """ + noPeriodo("desligamento_concluido_em") + doAnalista("projetista_id"),
                de, ate, analistaId);
    }

    long vistoriasAprovadas(LocalDate de, LocalDate ate, Long analistaId) {
        return contar("""
                SELECT COUNT(*) FROM vistoria v
                JOIN projeto p ON p.id = v.projeto_id
                WHERE v.status = 'APROVADA' AND v.data_resultado IS NOT NULL
                """ + noPeriodo("v.data_resultado") + doAnalista("p.analista_responsavel_id"),
                de, ate, analistaId);
    }

    /**
     * Do histórico, e não do status atual: uma vistoria reprovada e depois aprovada continua
     * tendo sido reprovada no período — é o retrabalho que o gestor quer enxergar.
     */
    long vistoriasReprovadas(LocalDate de, LocalDate ate, Long analistaId) {
        return contar("""
                SELECT COUNT(DISTINCT h.entidade_id) FROM historico_status h
                LEFT JOIN vistoria v ON v.id = h.entidade_id
                LEFT JOIN projeto p ON p.id = v.projeto_id
                WHERE h.entidade_tipo = 'VISTORIA' AND h.status_novo = 'REPROVADA'
                """ + noPeriodo("h.ocorrido_em") + doAnalista("p.analista_responsavel_id"),
                de, ate, analistaId);
    }

    long vistoriasSolicitadas(LocalDate de, LocalDate ate, Long analistaId) {
        return contar("""
                SELECT COUNT(*) FROM vistoria v
                JOIN projeto p ON p.id = v.projeto_id
                WHERE 1 = 1
                """ + noPeriodo("v.data_solicitacao") + doAnalista("p.analista_responsavel_id"),
                de, ate, analistaId);
    }

    private Double media(String sql, LocalDate de, LocalDate ate, Long analistaId) {
        Number resultado = (Number) comParametros(sql, de, ate, analistaId).getSingleResult();
        return resultado == null ? null : resultado.doubleValue();
    }

    private long contar(String sql, LocalDate de, LocalDate ate, Long analistaId) {
        return ((Number) comParametros(sql, de, ate, analistaId).getSingleResult()).longValue();
    }

    private Query comParametros(String sql, LocalDate de, LocalDate ate, Long analistaId) {
        return entityManager.createNativeQuery(sql)
                .setParameter("de", de)
                .setParameter("ate", ate)
                .setParameter("analistaId", analistaId);
    }
}
