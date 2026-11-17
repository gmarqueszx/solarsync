package com.conectsol.solarsync.auditoria;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.conectsol.solarsync.auditoria.dto.Alteracao;
import com.conectsol.solarsync.auditoria.dto.LogAtividadeFiltro;
import com.conectsol.solarsync.auditoria.dto.LogAtividadeResponse;
import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.common.FusoDaOperacao;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leitura do {@code log_atividade}. Por JDBC, e não JPA, pela mesma razão da escrita: uma
 * entidade aqui seria vista pelo próprio {@link LogAtividadeHibernateListener}. Sem método de
 * escrita — quem escreve é só o listener.
 */
@Repository
@RequiredArgsConstructor
public class LogAtividadeRepository {

    private static final TypeReference<List<Alteracao>> LISTA_DE_ALTERACOES =
            new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public Page<LogAtividadeResponse> listar(LogAtividadeFiltro filtro, Pageable paginacao) {
        MapSqlParameterSource parametros = new MapSqlParameterSource();
        List<String> condicoes = new ArrayList<>();

        if (filtro.usuarioId() != null) {
            condicoes.add("usuario_id = :usuarioId");
            parametros.addValue("usuarioId", filtro.usuarioId());
        }
        if (filtro.entidadeTipo() != null) {
            condicoes.add("entidade_tipo = :entidadeTipo");
            parametros.addValue("entidadeTipo", filtro.entidadeTipo().name());
        }
        if (filtro.entidadeId() != null) {
            condicoes.add("entidade_id = :entidadeId");
            parametros.addValue("entidadeId", filtro.entidadeId());
        }
        if (filtro.acao() != null) {
            condicoes.add("acao = :acao");
            parametros.addValue("acao", filtro.acao().name());
        }
        if (filtro.de() != null) {
            condicoes.add("ocorrido_em >= :de");
            parametros.addValue("de", java.sql.Timestamp.from(
                    filtro.de().atStartOfDay(FusoDaOperacao.ZONA).toInstant()));
        }
        if (filtro.ate() != null) {
            condicoes.add("ocorrido_em < :ate");
            parametros.addValue("ate", java.sql.Timestamp.from(
                    filtro.ate().plusDays(1).atStartOfDay(FusoDaOperacao.ZONA).toInstant()));
        }
        if (filtro.q() != null && !filtro.q().isBlank()) {
            condicoes.add("descricao ILIKE :q");
            parametros.addValue("q", "%" + filtro.q().trim() + "%");
        }

        String where = condicoes.isEmpty() ? "" : " WHERE " + String.join(" AND ", condicoes);

        Long total = jdbc.queryForObject("SELECT count(*) FROM log_atividade" + where,
                parametros, Long.class);

        parametros.addValue("limite", paginacao.getPageSize());
        parametros.addValue("deslocamento", paginacao.getOffset());
        String consulta = "SELECT id, ocorrido_em, usuario_id, usuario_nome, acao, entidade_tipo,"
                + " entidade_id, descricao, alteracoes::text AS alteracoes"
                + " FROM log_atividade" + where
                + " ORDER BY ocorrido_em DESC, id DESC LIMIT :limite OFFSET :deslocamento";
        List<LogAtividadeResponse> linhas = jdbc.query(consulta, parametros, this::mapear);

        return new PageImpl<>(linhas, paginacao, total == null ? 0 : total);
    }

    private LogAtividadeResponse mapear(ResultSet rs, int linha) throws SQLException {
        long usuarioId = rs.getLong("usuario_id");
        Long autor = rs.wasNull() ? null : usuarioId;
        return new LogAtividadeResponse(
                rs.getLong("id"),
                rs.getTimestamp("ocorrido_em").toInstant(),
                autor,
                rs.getString("usuario_nome"),
                AcaoLog.valueOf(rs.getString("acao")),
                EntidadeTipo.valueOf(rs.getString("entidade_tipo")),
                rs.getLong("entidade_id"),
                rs.getString("descricao"),
                jsonMapper.readValue(rs.getString("alteracoes"), LISTA_DE_ALTERACOES));
    }
}
