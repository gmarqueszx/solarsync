package com.conectsol.solarsync.pendencia;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.conectsol.solarsync.debito.Debito;
import com.conectsol.solarsync.debito.StatusDebito;
import com.conectsol.solarsync.debito.TipoDebito;
import com.conectsol.solarsync.pendencia.dto.PendenciaFiltro;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/** Traduz o {@link PendenciaFiltro} em Specification, combinando só os filtros informados. */
final class PendenciaSpecs {

    private PendenciaSpecs() {
    }

    static Specification<Pendencia> de(PendenciaFiltro filtro) {
        if (filtro == null) {
            return Specification.unrestricted();
        }

        List<Specification<Pendencia>> filtros = new ArrayList<>();

        if (filtro.clienteId() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.equal(raiz.get("cliente").get("id"), filtro.clienteId()));
        }
        if (filtro.status() != null && !filtro.status().isEmpty()) {
            filtros.add((raiz, consulta, cb) -> raiz.get("status").in(filtro.status()));
        }
        if (filtro.tipo() != null) {
            filtros.add((raiz, consulta, cb) -> cb.equal(raiz.get("tipo"), filtro.tipo()));
        }
        if (filtro.responsavelId() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.equal(raiz.get("responsavel").get("id"), filtro.responsavelId()));
        }
        if (filtro.q() != null && !filtro.q().isBlank()) {
            String padrao = "%" + filtro.q().trim().toLowerCase() + "%";
            filtros.add((raiz, consulta, cb) ->
                    cb.like(cb.lower(raiz.get("cliente").get("nome")), padrao));
        }
        if (filtro.solicitadoDe() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.greaterThanOrEqualTo(raiz.get("solicitadoEm"), filtro.solicitadoDe()));
        }
        if (filtro.solicitadoAte() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.lessThanOrEqualTo(raiz.get("solicitadoEm"), filtro.solicitadoAte()));
        }

        if (Boolean.TRUE.equals(filtro.travadaPorDebito())) {
            // Travada = ainda por resolver E cliente com débito ativo. Sem o recorte de status,
            // a fila incluiria pendência já resolvida ou cancelada, que não trava nada.
            filtros.add((raiz, consulta, cb) -> cb.and(
                    raiz.get("status").in(StatusPendencia.ABERTA, StatusPendencia.EM_ANDAMENTO),
                    cb.exists(debitoDoCliente(raiz, consulta, cb, StatusDebito.ATIVO))));
        }
        if (Boolean.TRUE.equals(filtro.semConsultaDebito())) {
            filtros.add((raiz, consulta, cb) -> cb.and(
                    raiz.get("status").in(StatusPendencia.ABERTA, StatusPendencia.EM_ANDAMENTO),
                    cb.not(cb.exists(debitoDoCliente(raiz, consulta, cb, null)))));
        }

        return filtros.isEmpty() ? Specification.unrestricted() : Specification.allOf(filtros);
    }

    /**
     * Subconsulta do débito de <b>pendência</b> do cliente; {@code status} nulo casa qualquer
     * consulta registrada. O recorte por tipo é essencial: um débito que trava a homologação não
     * trava a pendência, e sem ele a fila mostraria cliente parado por um motivo que quem
     * trabalha a pendência não tem como resolver.
     * <p>
     * Subconsulta em vez de join porque um join multiplicaria a linha da pendência e o
     * {@code EXISTS} negado (sem consulta) não funcionaria.
     */
    private static Subquery<Long> debitoDoCliente(Root<Pendencia> raiz, CriteriaQuery<?> consulta,
            CriteriaBuilder cb, StatusDebito status) {

        Subquery<Long> subconsulta = consulta.subquery(Long.class);
        Root<Debito> debito = subconsulta.from(Debito.class);
        Predicate mesmoClienteEPendencia = cb.and(
                cb.equal(debito.get("cliente").get("id"), raiz.get("cliente").get("id")),
                cb.equal(debito.get("tipo"), TipoDebito.PENDENCIA));

        return subconsulta.select(debito.get("id")).where(status == null
                ? mesmoClienteEPendencia
                : cb.and(mesmoClienteEPendencia, cb.equal(debito.get("status"), status)));
    }
}
