package com.conectsol.solarsync.debito;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.conectsol.solarsync.debito.dto.DebitoFiltro;

final class DebitoSpecs {

    private DebitoSpecs() {
    }

    static Specification<Debito> de(DebitoFiltro filtro) {
        if (filtro == null) {
            return Specification.unrestricted();
        }

        List<Specification<Debito>> filtros = new ArrayList<>();

        if (filtro.clienteId() != null) {
            filtros.add((raiz, consulta, cb) ->
                    cb.equal(raiz.get("cliente").get("id"), filtro.clienteId()));
        }
        if (filtro.tipo() != null) {
            filtros.add((raiz, consulta, cb) -> cb.equal(raiz.get("tipo"), filtro.tipo()));
        }
        if (filtro.status() != null && !filtro.status().isEmpty()) {
            filtros.add((raiz, consulta, cb) -> raiz.get("status").in(filtro.status()));
        }
        if (filtro.q() != null && !filtro.q().isBlank()) {
            String padrao = "%" + filtro.q().trim().toLowerCase() + "%";
            filtros.add((raiz, consulta, cb) ->
                    cb.like(cb.lower(raiz.get("cliente").get("nome")), padrao));
        }
        if (filtro.consultadoAntesDe() != null) {
            filtros.add((raiz, consulta, cb) -> cb.or(
                    cb.isNull(raiz.get("ultimaConsultaEm")),
                    cb.lessThan(raiz.get("ultimaConsultaEm"), filtro.consultadoAntesDe())));
        }
        if (filtro.paradoHaMaisDeDias() != null) {
            // Só faz sentido sobre débito ativo: quitado não está parando ninguém, ainda que a
            // data de detecção continue lá para a tela mostrar quanto tempo travou.
            Instant limite = Instant.now().minus(filtro.paradoHaMaisDeDias(), ChronoUnit.DAYS);
            filtros.add((raiz, consulta, cb) -> cb.and(
                    cb.equal(raiz.get("status"), StatusDebito.ATIVO),
                    cb.lessThanOrEqualTo(raiz.get("detectadoEm"), limite)));
        }

        return filtros.isEmpty() ? Specification.unrestricted() : Specification.allOf(filtros);
    }
}
