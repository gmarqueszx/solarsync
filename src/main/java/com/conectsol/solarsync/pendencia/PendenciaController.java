package com.conectsol.solarsync.pendencia;

import java.net.URI;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import com.conectsol.solarsync.historico.HistoricoStatusService;
import com.conectsol.solarsync.historico.dto.HistoricoStatusResponse;
import com.conectsol.solarsync.common.web.MotivoRequest;
import com.conectsol.solarsync.pendencia.dto.ObservacaoRequest;
import com.conectsol.solarsync.pendencia.dto.PendenciaAtualizarRequest;
import com.conectsol.solarsync.pendencia.dto.PendenciaCriarRequest;
import com.conectsol.solarsync.pendencia.dto.PendenciaFiltro;
import com.conectsol.solarsync.pendencia.dto.PendenciaResponse;
import com.conectsol.solarsync.pendencia.dto.PendenciaResumoResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Adaptador HTTP da etapa 1 do fluxo. Não injeta repository: toda escrita passa pelo
 * {@link PendenciaService}, único ponto que publica evento de status (CLAUDE.md seção 3).
 * Transições são endpoints de ação, nunca campo de um PUT — assim o frontend não consegue
 * trocar status por fora do service.
 */
@RestController
@RequestMapping("/api/pendencias")
@RequiredArgsConstructor
@Tag(name = "Pendências", description = "Etapa 1: pendências do cliente na Coelba")
public class PendenciaController {

    private final PendenciaService pendenciaService;
    private final HistoricoStatusService historicoStatusService;

    @GetMapping
    @PodeLer
    @Operation(summary = "Lista pendências paginadas, com filtros combináveis")
    public PaginaResponse<PendenciaResumoResponse> listar(
            @Valid PendenciaFiltro filtro,
            @PageableDefault(size = 20, sort = "solicitadoEm", direction = Sort.Direction.DESC)
            Pageable paginacao) {

        return PaginaResponse.de(
                pendenciaService.listar(filtro, paginacao), PendenciaResumoResponse::de);
    }

    @GetMapping("/{id}")
    @PodeLer
    public PendenciaResponse buscar(@PathVariable Long id) {
        return PendenciaResponse.de(pendenciaService.buscar(id));
    }

    @GetMapping("/{id}/historico")
    @PodeLer
    @Operation(summary = "Linha do tempo das mudanças de status da pendência")
    public List<HistoricoStatusResponse> historico(@PathVariable Long id) {
        return historicoStatusService.listar(EntidadeTipo.PENDENCIA, id);
    }

    @PostMapping
    @PodeEscrever
    @Operation(summary = "Cria uma pendência com status ABERTA")
    public ResponseEntity<PendenciaResponse> criar(
            @RequestBody @Valid PendenciaCriarRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        Pendencia criada = pendenciaService.criar(requisicao, usuario.id());
        return ResponseEntity.created(URI.create("/api/pendencias/" + criada.getId()))
                .body(PendenciaResponse.de(criada));
    }

    @PutMapping("/{id}")
    @PodeEscrever
    @Operation(
            summary = "Edita os dados da pendência",
            description = "Não altera status: use os endpoints de ação.")
    public PendenciaResponse atualizar(@PathVariable Long id,
            @RequestBody @Valid PendenciaAtualizarRequest requisicao) {
        return PendenciaResponse.de(pendenciaService.atualizar(id, requisicao));
    }

    @DeleteMapping("/{id}")
    @SomenteAdministrador
    @Operation(
            summary = "Exclui a pendência",
            description = "Restrito a ADMINISTRADOR. O caminho normal para registro errado é "
                    + "cancelar por status, que preserva o histórico.")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        pendenciaService.excluir(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/iniciar")
    @PodeEscrever
    @Operation(summary = "Marca a pendência como EM_ANDAMENTO")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public PendenciaResponse iniciar(@PathVariable Long id,
            @RequestBody(required = false) @Valid ObservacaoRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return mudarStatus(id, StatusPendencia.EM_ANDAMENTO, usuario,
                requisicao == null ? null : requisicao.observacao());
    }

    @PostMapping("/{id}/resolver")
    @PodeEscrever
    @Operation(
            summary = "Marca a pendência como RESOLVIDA",
            description = "Dispara a criação automática do Projeto do cliente (CLAUDE.md seção "
                    + "3). Se já houver projeto em andamento, nenhum novo é criado — então esta "
                    + "resposta não garante projeto novo; recarregue a lista de projetos.")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public PendenciaResponse resolver(@PathVariable Long id,
            @RequestBody(required = false) @Valid ObservacaoRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return mudarStatus(id, StatusPendencia.RESOLVIDA, usuario,
                requisicao == null ? null : requisicao.observacao());
    }

    @PostMapping("/{id}/cancelar")
    @PodeEscrever
    @Operation(summary = "Cancela a pendência; o motivo fica no histórico")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public PendenciaResponse cancelar(@PathVariable Long id,
            @RequestBody @Valid MotivoRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return mudarStatus(id, StatusPendencia.CANCELADA, usuario, requisicao.motivo());
    }

    @PostMapping("/{id}/reabrir")
    @PodeEscrever
    @Operation(summary = "Volta a pendência para ABERTA")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public PendenciaResponse reabrir(@PathVariable Long id,
            @RequestBody(required = false) @Valid ObservacaoRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return mudarStatus(id, StatusPendencia.ABERTA, usuario,
                requisicao == null ? null : requisicao.observacao());
    }

    private PendenciaResponse mudarStatus(Long id, StatusPendencia novoStatus,
            UsuarioAutenticado usuario, String observacao) {
        return PendenciaResponse.de(
                pendenciaService.atualizarStatus(id, novoStatus, usuario.id(), observacao));
    }
}
