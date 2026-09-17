package com.conectsol.solarsync.dashboard;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.dashboard.dto.DashboardResponse;
import com.conectsol.solarsync.dashboard.dto.DashboardResponse.Filtro;
import com.conectsol.solarsync.dashboard.dto.DashboardResponse.Periodo;
import com.conectsol.solarsync.dashboard.dto.DashboardResponse.Quantitativos;
import com.conectsol.solarsync.dashboard.dto.DashboardResponse.TemposMediosEmDias;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardRepository dashboardRepository;
    private final UsuarioRepository usuarioRepository;

    @Transactional(readOnly = true)
    public DashboardResponse metricas(LocalDate de, LocalDate ate, Long analistaId) {
        return new DashboardResponse(
                new Periodo(de, ate),
                new Filtro(analistaId, nomeDoAnalista(analistaId)),
                new TemposMediosEmDias(
                        arredondar(dashboardRepository
                                .mediaDiasSemNinguemMexerNoCliente(de, ate, analistaId)),
                        arredondar(dashboardRepository
                                .mediaDiasResolucaoDePendencia(de, ate, analistaId)),
                        arredondar(dashboardRepository
                                .mediaDiasRecebimentoAteEnvio(de, ate, analistaId)),
                        arredondar(dashboardRepository
                                .mediaDiasEnvioAteAprovacao(de, ate, analistaId)),
                        arredondar(dashboardRepository
                                .mediaDiasParadoPorDebito(de, ate, analistaId)),
                        arredondar(dashboardRepository
                                .mediaDiasInstalacaoAteSolicitarVistoria(de, ate, analistaId)),
                        arredondar(dashboardRepository
                                .mediaDiasEsperaDoDesligamento(de, ate, analistaId)),
                        arredondar(dashboardRepository
                                .mediaDiasCicloCompleto(de, ate, analistaId))),
                new Quantitativos(
                        dashboardRepository.pendenciasAbertasNoPeriodo(de, ate, analistaId),
                        dashboardRepository.pendenciasResolvidas(de, ate, analistaId),
                        dashboardRepository.projetosEncaminhados(de, ate, analistaId),
                        dashboardRepository.projetosReencaminhados(de, ate, analistaId),
                        dashboardRepository.projetosAprovados(de, ate, analistaId),
                        dashboardRepository.projetosReprovados(de, ate, analistaId),
                        dashboardRepository.clientesComDebitoAtivo(analistaId),
                        dashboardRepository.clientesTravadosNaPendencia(analistaId),
                        dashboardRepository.clientesTravadosNaHomologacao(analistaId),
                        dashboardRepository.clientesComDebitoQuitado(analistaId),
                        dashboardRepository.vistoriasSolicitadas(de, ate, analistaId),
                        dashboardRepository.vistoriasAprovadas(de, ate, analistaId),
                        dashboardRepository.vistoriasReprovadas(de, ate, analistaId),
                        dashboardRepository.unificacoesPendentes(analistaId),
                        dashboardRepository.desligamentosAguardando(analistaId),
                        dashboardRepository.desligamentosComOsAberta(analistaId),
                        dashboardRepository.desligamentosConcluidos(de, ate, analistaId)));
    }

    /**
     * Um id inexistente devolveria todos os números zerados, que a tela não teria como
     * distinguir de "esse analista não fez nada no período". 404 é a resposta honesta.
     */
    private String nomeDoAnalista(Long analistaId) {
        if (analistaId == null) {
            return null;
        }
        return usuarioRepository.findById(analistaId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Usuário não encontrado: " + analistaId))
                .getNome();
    }

    /** Uma casa decimal: "12,4 dias" informa; "12,428571428" só polui a tela. */
    private static Double arredondar(Double valor) {
        return valor == null ? null : Math.round(valor * 10.0) / 10.0;
    }
}
