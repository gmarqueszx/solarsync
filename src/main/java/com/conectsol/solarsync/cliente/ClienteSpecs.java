package com.conectsol.solarsync.cliente;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.conectsol.solarsync.cliente.dto.ClienteFiltro;
import com.conectsol.solarsync.debito.Debito;

import jakarta.persistence.criteria.Subquery;

final class ClienteSpecs {

    private ClienteSpecs() {
    }

    static Specification<Cliente> de(ClienteFiltro filtro) {
        if (filtro == null) {
            return Specification.unrestricted();
        }

        List<Specification<Cliente>> filtros = new ArrayList<>();

        if (filtro.nome() != null && !filtro.nome().isBlank()) {
            String padrao = "%" + filtro.nome().trim().toLowerCase() + "%";
            filtros.add((raiz, consulta, cb) -> cb.or(
                    cb.like(cb.lower(raiz.get("nome")), padrao),
                    cb.like(cb.lower(raiz.get("ucCoelba")), padrao)));
        }
        if (filtro.statusTriagem() != null && !filtro.statusTriagem().isEmpty()) {
            filtros.add((raiz, consulta, cb) ->
                    raiz.get("statusTriagem").in(filtro.statusTriagem()));
        }
        if (filtro.semConsultaDebito() != null) {
            // Subconsulta em vez de LEFT JOIN: com join, um cliente com consulta apareceria na
            // página mesmo filtrando "sem consulta", porque o predicado cairia na linha do join.
            filtros.add((raiz, consulta, cb) -> {
                Subquery<Long> temDebito = consulta.subquery(Long.class);
                var debito = temDebito.from(Debito.class);
                temDebito.select(debito.get("id"))
                        .where(cb.equal(debito.get("cliente").get("id"), raiz.get("id")));
                return filtro.semConsultaDebito()
                        ? cb.not(cb.exists(temDebito))
                        : cb.exists(temDebito);
            });
        }

        return filtros.isEmpty() ? Specification.unrestricted() : Specification.allOf(filtros);
    }
}
