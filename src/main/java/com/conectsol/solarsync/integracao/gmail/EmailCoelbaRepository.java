package com.conectsol.solarsync.integracao.gmail;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmailCoelbaRepository extends JpaRepository<EmailCoelba, Long> {

    /**
     * Quais dos ids listados já foram processados. Em lote e não um a um: o job lista até algumas
     * dezenas de mensagens por execução e quase todas já são conhecidas, então isto é o que
     * evita uma consulta por mensagem só para descobrir que não há nada a fazer.
     * <p>
     * ⚠️ {@code CONFERENCIA} <b>não</b> conta como processado. É o registro de um e-mail que
     * teria mudado um projeto e não mudou, porque o modo conferência estava ligado — então,
     * desligado o modo, ele precisa voltar a ser lido para enfim ser aplicado. Sem esta
     * exclusão, o ensaio consumiria em silêncio justamente os e-mails que importavam.
     * <p>
     * ⚠️ Nem {@code SEM_CORRESPONDENCIA} com número ({@link EmailCoelba#aguardaProjeto()}). O
     * e-mail chega antes de o projeto ter aquele número no SolarSync sempre que a solicitação é
     * lançada aqui depois de a Coelba responder — e, contado como processado, nunca mais seria
     * lido: o projeto ficaria em {@code ENCAMINHADO} com a aprovação já na caixa. Volta a ser
     * lido enquanto estiver na janela da consulta ({@code newer_than}).
     * <p>
     * {@code TRANSICAO_INVALIDA} continua definitivo, de propósito. O portal manda notificação
     * duplicada e fora de ordem: um cancelamento atrasado barrado com o projeto aguardando
     * envio seria reaplicado assim que ele fosse encaminhado, reprovando o envio novo.
     */
    @Query("""
            select e.mensagemId from EmailCoelba e
            where e.mensagemId in :ids
              and e.resultado <> com.conectsol.solarsync.integracao.gmail.ResultadoProcessamento.CONFERENCIA
              and not (e.resultado = com.conectsol.solarsync.integracao.gmail.ResultadoProcessamento.SEM_CORRESPONDENCIA
                       and e.numeroSolicitacao is not null)
            """)
    List<String> idsJaProcessados(Collection<String> ids);

    /** Para a releitura atualizar o registro em vez de criar outro. */
    Optional<EmailCoelba> findByMensagemId(String mensagemId);
}
