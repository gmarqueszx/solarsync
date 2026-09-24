package com.conectsol.solarsync.projeto;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

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
}
