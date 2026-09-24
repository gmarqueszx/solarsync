package com.conectsol.solarsync.common.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Põe o cliente prioritário no topo de qualquer listagem, antes da ordenação que o usuário
 * escolheu.
 * <p>
 * Existe como peça única porque o requisito é o mesmo em todas as etapas — "sempre que um cliente
 * estiver marcado como prioridade, ele deve aparecer no topo da etapa em que estiver,
 * independentemente da etapa atual" — e repetir a regra em seis services é garantir que um deles
 * fique para trás no dia em que ela mudar. Cada service só informa <b>onde</b> fica a flag a
 * partir da sua raiz: {@code "prioridade"} no próprio cliente, {@code "cliente.prioridade"} nas
 * entidades que o referenciam, {@code "projeto.cliente.prioridade"} na vistoria.
 * <p>
 * É prefixo, não substituição: a ordenação pedida pelo frontend continua valendo <b>dentro</b> de
 * cada grupo, então clicar numa coluna reordena os prioritários entre si e os normais entre si,
 * sem misturá-los. Ordenar por prioridade é do sistema; ordenar por coluna é de quem olha.
 * <p>
 * {@code DESC} porque em SQL {@code false &lt; true} — decrescente é o que traz o {@code true}
 * primeiro. A coluna é {@code NOT NULL}, então não há caso de nulo a tratar.
 */
public final class PrioridadePrimeiro {

    private PrioridadePrimeiro() {
    }

    /**
     * @param paginacao a paginação que chegou do controller, com a ordenação do usuário
     * @param caminho   caminho da flag a partir da raiz da consulta (ex.: {@code cliente.prioridade})
     */
    public static Pageable aplicar(Pageable paginacao, String caminho) {
        if (paginacao == null || paginacao.isUnpaged()) {
            return paginacao;
        }
        Sort comPrioridade = Sort.by(Sort.Order.desc(caminho)).and(paginacao.getSort());
        return PageRequest.of(paginacao.getPageNumber(), paginacao.getPageSize(), comPrioridade);
    }
}
