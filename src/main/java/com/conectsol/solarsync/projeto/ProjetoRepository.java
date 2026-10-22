package com.conectsol.solarsync.projeto;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjetoRepository
        extends JpaRepository<Projeto, Long>, JpaSpecificationExecutor<Projeto> {

    List<Projeto> findByClienteId(Long clienteId);

    Optional<Projeto> findFirstByClienteIdOrderByCriadoEmDesc(Long clienteId);

    /**
     * Casa o retorno da Coelba com o projeto pelo número da solicitação (seção 9). Recebe uma
     * coleção porque um e-mail pode trazer mais de um número candidato, e é o casamento com um
     * projeto existente — não o formato — que decide qual deles é o número de verdade.
     * <p>
     * Devolve lista, e não {@code Optional}: {@code numero_solicitacao} não é único de propósito
     * (reenvio pode receber outro número, e a importação da planilha traz o campo irregular),
     * então "mais de um projeto com este número" é um resultado possível — e é o que faz a
     * integração recusar aplicar em vez de escolher no escuro.
     */
    List<Projeto> findByNumeroSolicitacaoIn(Collection<String> numerosSolicitacao);

    /**
     * Os números de solicitação que ainda esperam alguma notícia da Coelba. É com eles que a
     * leitura do Gmail recorta a busca, em vez de varrer tudo que o portal mandou (seção 9):
     * ~30 e-mails por dia, dos quais só os destes projetos podem virar mudança de status.
     * <p>
     * <b>Duas esperas, não uma.</b> "Encaminhado" cobre o retorno da homologação; a fase de
     * vistoria cobre a etapa 4, que chega quando o projeto já está APROVADO — fora de
     * {@code ENCAMINHADO}. Filtrar só pelos encaminhados desligaria a automação da vistoria em
     * silêncio, que é o tipo de regressão que não aparece em teste nenhum porque o e-mail
     * simplesmente deixa de ser buscado.
     * <p>
     * Vistoria {@code APROVADA} sai da lista: dali não vem mais nada do portal que mude algo
     * aqui. É o que impede a lista de crescer para sempre e a consulta do Gmail de estourar.
     */
    @Query("""
            select distinct p.numeroSolicitacao from Projeto p
            where p.numeroSolicitacao is not null
              and (
                    p.status in (com.conectsol.solarsync.projeto.StatusProjeto.ENCAMINHADO,
                                 com.conectsol.solarsync.projeto.StatusProjeto.REENCAMINHADO)
                 or exists (select 1 from Vistoria v
                            where v.projeto = p
                              and v.status <> com.conectsol.solarsync.vistoria.StatusVistoria.APROVADA)
              )
            """)
    List<String> numerosAguardandoRetornoDaCoelba();

    /**
     * Os números dos projetos que foram encaminhados à Coelba a partir de {@code desde} e ainda
     * esperam o primeiro retorno. É com eles que a leitura do Gmail faz a busca <b>sem limite de
     * data</b> (22/10/2026).
     * <p>
     * Existe por causa do envio registrado com atraso: a solicitação 2610021352 foi aprovada na
     * Coelba em 07/10 e só marcada como encaminhada aqui em 22/10. A consulta normal olha sete
     * dias para trás, então a aprovação já tinha saído da janela quando o projeto passou a
     * esperá-la, e nem chegou a {@code email_coelba}.
     * <p>
     * <b>Só o primeiro envio</b> ({@code ENCAMINHADO}), e não o reenvio. Num reenvio com o número
     * do ciclo anterior, a busca sem data traria também os avisos daquele ciclo, e um
     * cancelamento duplicado que nunca foi lido reprovaria o envio novo. No primeiro envio não há
     * ciclo anterior para confundir.
     * <p>
     * O instante sai de {@code historico_status} e não de {@code data_encaminhado}, que é data de
     * negócio e pode ser retroativa: o que se quer saber é quando o projeto <b>passou</b> a
     * esperar aqui dentro. O projeto que já saiu de {@code ENCAMINHADO} sai da lista: o retorno
     * chegou e não há mais o que buscar.
     */
    @Query("""
            select distinct p.numeroSolicitacao from Projeto p, HistoricoStatus h
            where h.entidadeTipo = com.conectsol.solarsync.common.EntidadeTipo.PROJETO
              and h.entidadeId = p.id
              and h.statusNovo = 'ENCAMINHADO'
              and h.ocorridoEm >= :desde
              and p.status = com.conectsol.solarsync.projeto.StatusProjeto.ENCAMINHADO
              and p.numeroSolicitacao is not null
            """)
    List<String> numerosEncaminhadosDesde(@Param("desde") Instant desde);
}
