package com.conectsol.solarsync.debito;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.common.security.Autenticado;
import com.conectsol.solarsync.common.security.PodeEscrever;
import com.conectsol.solarsync.common.security.PodeLer;
import com.conectsol.solarsync.common.security.SomenteAdministrador;
import com.conectsol.solarsync.common.security.UsuarioAutenticado;
import com.conectsol.solarsync.common.web.PaginaResponse;
import com.conectsol.solarsync.debito.dto.DebitoFiltro;
import com.conectsol.solarsync.debito.dto.DebitoRegistrarRequest;
import com.conectsol.solarsync.debito.dto.DebitoResponse;
import com.conectsol.solarsync.historico.HistoricoStatusService;
import com.conectsol.solarsync.historico.dto.HistoricoStatusResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Etapa 2 do fluxo. Não há POST: o débito é identificado pelo par cliente + tipo (um registro
 * por combinação), então registrar uma consulta é um {@code PUT} idempotente em
 * {@code /api/debitos/cliente/{clienteId}} com o tipo no corpo — consultar de novo atualiza o
 * mesmo registro em vez de criar linhas soltas.
 */
@RestController
@RequestMapping("/api/debitos")
@RequiredArgsConstructor
@Tag(name = "Débitos", description = "Etapa 2: situação de débito do cliente na Coelba")
public class DebitoController {

    private final DebitoService debitoService;
    private final HistoricoStatusService historicoStatusService;

    @GetMapping
    @PodeLer
    @Operation(
            summary = "Lista as situações de débito conhecidas",
            description = "Filtre por tipo=PENDENCIA/HOMOLOGACAO para ver o que está travando "
                    + "cada etapa, por status=ATIVO para ver quem está travado, por "
                    + "paradoHaMaisDeDias para a fila do financeiro, ou use consultadoAntesDe "
                    + "para achar consultas velhas que valem refazer.")
    public PaginaResponse<DebitoResponse> listar(
            @Valid DebitoFiltro filtro,
            @PageableDefault(size = 20, sort = "ultimaConsultaEm", direction = Sort.Direction.DESC)
            Pageable paginacao) {

        return PaginaResponse.de(debitoService.listar(filtro, paginacao), DebitoResponse::de);
    }

    @GetMapping("/cliente/{clienteId}")
    @PodeLer
    @Operation(
            summary = "Situações de débito de um cliente",
            description = "Até dois registros: o débito que trava a pendência e o que trava a "
                    + "homologação. Lista vazia significa que ninguém consultou este cliente "
                    + "ainda — que é diferente de não dever nada.")
    public List<DebitoResponse> buscarPorCliente(@PathVariable Long clienteId) {
        return debitoService.listarPorCliente(clienteId).stream()
                .map(DebitoResponse::de)
                .toList();
    }

    @PutMapping("/cliente/{clienteId}")
    @PodeEscrever
    @Operation(
            summary = "Registra o resultado da consulta de débito",
            description = "O tipo diz que etapa esta consulta responde. Com PENDENCIA ativo, "
                    + "resolver a pendência é bloqueado com 409; com HOMOLOGACAO ativo, "
                    + "encaminhar o projeto à Coelba é bloqueado com 409. Reconsultar e achar a "
                    + "mesma situação só atualiza a data, sem gerar linha nova de histórico.")
    public DebitoResponse registrarConsulta(@PathVariable Long clienteId,
            @RequestBody @Valid DebitoRegistrarRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return DebitoResponse.de(
                debitoService.registrarConsulta(clienteId, requisicao, usuario.id()));
    }

    @GetMapping("/{id}/historico")
    @PodeLer
    @Operation(
            summary = "Linha do tempo do débito",
            description = "É desta linha do tempo que sai a métrica de tempo parado por débito.")
    public List<HistoricoStatusResponse> historico(@PathVariable Long id) {
        return historicoStatusService.listar(EntidadeTipo.DEBITO, id);
    }

    @DeleteMapping("/{id}")
    @SomenteAdministrador
    @Operation(
            summary = "Exclui o registro de débito",
            description = "Restrito a ADMINISTRADOR. O caminho normal é registrar QUITADO, que "
                    + "preserva o histórico.")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        debitoService.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
