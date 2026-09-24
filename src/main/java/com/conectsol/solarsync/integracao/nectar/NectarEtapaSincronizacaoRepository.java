package com.conectsol.solarsync.integracao.nectar;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NectarEtapaSincronizacaoRepository
        extends JpaRepository<NectarEtapaSincronizacao, Long> {

    /**
     * A última tentativa registrada para o cliente. É a primeira camada de idempotência: se ela
     * diz que o cliente já foi posto na etapa pretendida, não há requisição nenhuma ao CRM.
     * <p>
     * A segunda camada é o próprio CRM, consultado antes do PUT — esta aqui só evita a viagem.
     */
    Optional<NectarEtapaSincronizacao> findFirstByClienteIdOrderByOcorridoEmDescIdDesc(
            Long clienteId);

    /**
     * A fila de reprocessamento: falhas que ainda são a última palavra sobre aquele cliente.
     * <p>
     * O recorte "ainda é a última" é o que impede o retry de empurrar o CRM para trás. Um projeto
     * que falhou ao ir para ENCAMINHADO e desde então foi aprovado já tem uma linha mais nova;
     * repetir a antiga devolveria o cliente à etapa de encaminhado no CRM, e a integração
     * passaria a mentir com a melhor das intenções.
     */
    @Query("""
            select s from NectarEtapaSincronizacao s
            where s.resultado = com.conectsol.solarsync.integracao.nectar.ResultadoSincronizacao.ERRO
              and s.id = (select max(u.id) from NectarEtapaSincronizacao u
                          where u.clienteId = s.clienteId)
            order by s.ocorridoEm
            """)
    List<NectarEtapaSincronizacao> falhasPendentes(Pageable limite);
}
