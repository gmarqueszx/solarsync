package com.conectsol.solarsync.projeto;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.conectsol.solarsync.debito.Debito;
import com.conectsol.solarsync.debito.StatusDebito;
import com.conectsol.solarsync.debito.TipoDebito;
import com.conectsol.solarsync.projeto.dto.ProjetoFiltro;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

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
            // Nome, UC ou número de solicitação: quando o retorno da Coelba chega, o analista
            // tem em mãos o número, não o nome — e é por ele que precisa achar o projeto.
            String padrao = "%" + filtro.q().trim().toLowerCase() + "%";
            filtros.add((raiz, consulta, cb) -> cb.or(
                    cb.like(cb.lower(raiz.get("cliente").get("nome")), padrao),
                    cb.like(cb.lower(raiz.get("cliente").get("ucCoelba")), padrao),
                    cb.like(cb.lower(raiz.get("numeroSolicitacao")), padrao)));
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

        if (Boolean.TRUE.equals(filtro.travadoPorDebito())) {
            // Travado = ainda por enviar E cliente com débito de homologação ativo. Sem o
            // recorte de status a fila incluiria projeto já enviado ou aprovado, que o débito
            // não trava mais.
            filtros.add((raiz, consulta, cb) -> cb.and(
                    raiz.get("status").in(StatusProjeto.RECEBIDO, StatusProjeto.AGUARDANDO_ENVIO,
                            StatusProjeto.REPROVADO),
                    cb.exists(debitoDeHomologacao(raiz, consulta, cb, StatusDebito.ATIVO))));
        }
        if (Boolean.TRUE.equals(filtro.semConsultaDebito())) {
            filtros.add((raiz, consulta, cb) -> cb.and(
                    raiz.get("status").in(StatusProjeto.RECEBIDO, StatusProjeto.AGUARDANDO_ENVIO,
                            StatusProjeto.REPROVADO),
                    cb.not(cb.exists(debitoDeHomologacao(raiz, consulta, cb, null)))));
        }

        return filtros.isEmpty() ? Specification.unrestricted() : Specification.allOf(filtros);
    }

    /**
     * Subconsulta do débito de <b>homologação</b> do cliente do projeto; {@code status} nulo casa
     * qualquer consulta registrada. Subconsulta em vez de join porque um join multiplicaria a
     * linha do projeto e o {@code EXISTS} negado (sem consulta) não funcionaria.
     */
    private static Subquery<Long> debitoDeHomologacao(Root<Projeto> raiz, CriteriaQuery<?> consulta,
            CriteriaBuilder cb, StatusDebito status) {

        Subquery<Long> subconsulta = consulta.subquery(Long.class);
        Root<Debito> debito = subconsulta.from(Debito.class);
        Predicate mesmoClienteEHomologacao = cb.and(
                cb.equal(debito.get("cliente").get("id"), raiz.get("cliente").get("id")),
                cb.equal(debito.get("tipo"), TipoDebito.HOMOLOGACAO));

        return subconsulta.select(debito.get("id")).where(status == null
                ? mesmoClienteEHomologacao
                : cb.and(mesmoClienteEHomologacao, cb.equal(debito.get("status"), status)));
    }
}
