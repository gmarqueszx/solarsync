package com.conectsol.solarsync.dashboard;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.dashboard.dto.DashboardResponse;
import com.conectsol.solarsync.dashboard.dto.DashboardResponse.Periodo;
import com.conectsol.solarsync.dashboard.dto.DashboardResponse.Quantitativos;
import com.conectsol.solarsync.dashboard.dto.DashboardResponse.TemposMediosEmDias;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardRepository dashboardRepository;

    @Transactional(readOnly = true)
    public DashboardResponse metricas(LocalDate de, LocalDate ate) {
        return new DashboardResponse(
                new Periodo(de, ate),
                new TemposMediosEmDias(
                        arredondar(dashboardRepository.mediaDiasSemNinguemMexerNoCliente(de, ate)),
                        arredondar(dashboardRepository.mediaDiasResolucaoDePendencia(de, ate)),
                        arredondar(dashboardRepository.mediaDiasRecebimentoAteEnvio(de, ate)),
                        arredondar(dashboardRepository.mediaDiasEnvioAteAprovacao(de, ate)),
                        arredondar(dashboardRepository.mediaDiasParadoPorDebito(de, ate)),
                        arredondar(dashboardRepository
                                .mediaDiasInstalacaoAteSolicitarVistoria(de, ate)),
                        arredondar(dashboardRepository.mediaDiasCicloCompleto(de, ate))),
                new Quantitativos(
                        dashboardRepository.pendenciasAbertasNoPeriodo(de, ate),
                        dashboardRepository.pendenciasResolvidas(de, ate),
                        dashboardRepository.projetosEncaminhados(de, ate),
                        dashboardRepository.projetosReencaminhados(de, ate),
                        dashboardRepository.projetosAprovados(de, ate),
                        dashboardRepository.projetosReprovados(de, ate),
                        dashboardRepository.clientesComDebitoAtivo(),
                        dashboardRepository.clientesComDebitoQuitado(),
                        dashboardRepository.vistoriasSolicitadas(de, ate),
                        dashboardRepository.vistoriasAprovadas(de, ate),
                        dashboardRepository.vistoriasReprovadas(de, ate),
                        dashboardRepository.unificacoesPendentes()));
    }

    /** Uma casa decimal: "12,4 dias" informa; "12,428571428" só polui a tela. */
    private static Double arredondar(Double valor) {
        return valor == null ? null : Math.round(valor * 10.0) / 10.0;
    }
}
