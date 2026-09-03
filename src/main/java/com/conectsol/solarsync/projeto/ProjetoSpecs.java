package com.conectsol.solarsync.projeto;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.conectsol.solarsync.projeto.dto.ProjetoFiltro;

final class ProjetoSpecs {

    private ProjetoSpecs() {
    }

    static Specification<Projeto> de(ProjetoFiltro filtro) {
        if (filtro == null) {
            return Specification.unrestricted();
        }

        List<Specification<Projeto>> filtros = new ArrayList<>();

        if (filtro.clienteId() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.equal(raiz.get("cliente").get("id"), filtro.clienteId()));
        }
        if (filtro.status() != null && !filtro.status().isEmpty()) {
            filtros.add((raiz, consulta, cb) -> raiz.get("status").in(filtro.status()));
        }
        if (filtro.tipoProjeto() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.equal(raiz.get("tipoProjeto"), filtro.tipoProjeto()));
        }
        if (filtro.analistaResponsavelId() != null) {
            filtros.add((raiz, consulta, cb) -> cb.equal(
                    raiz.get("analistaResponsavel").get("id"), filtro.analistaResponsavelId()));
        }
        if (filtro.q() != null && !filtro.q().isBlank()) {
            String padrao = "%" + filtro.q().trim().toLowerCase() + "%";
            filtros.add((raiz, consulta, cb) ->
                    cb.like(cb.lower(raiz.get("cliente").get("nome")), padrao));
        }
        if (filtro.recebidoDe() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.greaterThanOrEqualTo(raiz.get("dataRecebimento"), filtro.recebidoDe()));
        }
        if (filtro.recebidoAte() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.lessThanOrEqualTo(raiz.get("dataRecebimento"), filtro.recebidoAte()));
        }

        return filtros.isEmpty() ? Specification.unrestricted() : Specification.allOf(filtros);
    }
}
