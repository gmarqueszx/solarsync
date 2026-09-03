package com.conectsol.solarsync.pendencia;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.conectsol.solarsync.pendencia.dto.PendenciaFiltro;

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

        return filtros.isEmpty() ? Specification.unrestricted() : Specification.allOf(filtros);
    }
}
