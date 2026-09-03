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
        if (filtro.instalado() != null) {
            filtros.add((raiz, consulta, cb) -> filtro.instalado()
                    ? cb.isNotNull(raiz.get("dataInstalacao"))
                    : cb.isNull(raiz.get("dataInstalacao")));
        }
        if (filtro.semVistoria() != null) {
            filtros.add((raiz, consulta, cb) -> {
                // Subconsulta em vez de join: um join deixaria o count da paginação errado
                // quando o projeto tem mais de uma vistoria.
                jakarta.persistence.criteria.Subquery<Long> vistorias =
                        consulta.subquery(Long.class);
                jakarta.persistence.criteria.Root<com.conectsol.solarsync.vistoria.Vistoria> v =
                        vistorias.from(com.conectsol.solarsync.vistoria.Vistoria.class);
                vistorias.select(cb.literal(1L))
                        .where(cb.equal(v.get("projeto").get("id"), raiz.get("id")));

                return filtro.semVistoria() ? cb.not(cb.exists(vistorias)) : cb.exists(vistorias);
            });
        }

        return filtros.isEmpty() ? Specification.unrestricted() : Specification.allOf(filtros);
    }
}
