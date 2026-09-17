package com.conectsol.solarsync.vistoria;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.conectsol.solarsync.vistoria.dto.VistoriaFiltro;

final class VistoriaSpecs {

    private VistoriaSpecs() {
    }

    static Specification<Vistoria> de(VistoriaFiltro filtro) {
        if (filtro == null) {
            return Specification.unrestricted();
        }

        List<Specification<Vistoria>> filtros = new ArrayList<>();

        if (filtro.projetoId() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.equal(raiz.get("projeto").get("id"), filtro.projetoId()));
        }
        if (filtro.clienteId() != null) {
            filtros.add((raiz, consulta, cb) -> cb.equal(
                    raiz.get("projeto").get("cliente").get("id"), filtro.clienteId()));
        }
        if (filtro.status() != null && !filtro.status().isEmpty()) {
            filtros.add((raiz, consulta, cb) -> raiz.get("status").in(filtro.status()));
        }
        if (filtro.q() != null && !filtro.q().isBlank()) {
            String padrao = "%" + filtro.q().trim().toLowerCase() + "%";
            filtros.add((raiz, consulta, cb) -> cb.like(
                    cb.lower(raiz.get("projeto").get("cliente").get("nome")), padrao));
        }
        if (filtro.solicitadaDe() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.greaterThanOrEqualTo(raiz.get("dataSolicitacao"), filtro.solicitadaDe()));
        }
        if (filtro.solicitadaAte() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.lessThanOrEqualTo(raiz.get("dataSolicitacao"), filtro.solicitadaAte()));
        }

        return filtros.isEmpty() ? Specification.unrestricted() : Specification.allOf(filtros);
    }
}
