package com.conectsol.solarsync.unificacao;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.conectsol.solarsync.unificacao.dto.UnificacaoFiltro;

final class UnificacaoSpecs {

    private UnificacaoSpecs() {
    }

    static Specification<Unificacao> de(UnificacaoFiltro filtro) {
        if (filtro == null) {
            return Specification.unrestricted();
        }

        List<Specification<Unificacao>> filtros = new ArrayList<>();

        if (filtro.clienteId() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.equal(raiz.get("cliente").get("id"), filtro.clienteId()));
        }
        if (filtro.projetistaId() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.equal(raiz.get("projetista").get("id"), filtro.projetistaId()));
        }
        if (filtro.cidade() != null && !filtro.cidade().isBlank()) {
            filtros.add((raiz, consulta, cb) -> cb.equal(
                    cb.lower(raiz.get("cidade")), filtro.cidade().trim().toLowerCase()));
        }
        if (filtro.feita() != null) {
            filtros.add((raiz, consulta, cb) -> cb.equal(raiz.get("feita"), filtro.feita()));
        }
        if (filtro.desligamento() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.equal(raiz.get("desligamento"), filtro.desligamento()));
        }
        if (filtro.q() != null && !filtro.q().isBlank()) {
            String padrao = "%" + filtro.q().trim().toLowerCase() + "%";
            filtros.add((raiz, consulta, cb) ->
                    cb.like(cb.lower(raiz.get("cliente").get("nome")), padrao));
        }

        return filtros.isEmpty() ? Specification.unrestricted() : Specification.allOf(filtros);
    }
}
