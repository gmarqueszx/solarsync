package com.conectsol.solarsync.dashboard;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conectsol.solarsync.common.security.SomenteGestor;
import com.conectsol.solarsync.dashboard.dto.DashboardResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Métricas gerenciais — restrito a GESTOR e ADMINISTRADOR")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    @SomenteGestor
    @Operation(
            summary = "Métricas de tempo de ciclo e quantitativos do período",
            description = """
                    As 13 métricas numa resposta só. Cada uma é filtrada pela sua própria data \
                    de referência — projetos aprovados pela data de aprovação, pendências \
                    resolvidas pela data de resolução —, respondendo "no período X, como foi o \
                    desempenho".

                    Tempos são médias em dias e podem vir nulos: nulo é "não houve caso no \
                    período", diferente de zero. A única exceção ao filtro é \
                    clientesComDebitoAtivo, que é a situação de agora — quantos clientes estão \
                    travados neste momento.

                    Sem de/ate, considera todo o histórico.""")
    @ApiResponse(responseCode = "403", description = "ANALISTA não tem acesso ao dashboard")
    public DashboardResponse metricas(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate ate) {

        return dashboardService.metricas(de, ate);
    }
}
