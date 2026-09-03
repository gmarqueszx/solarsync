package com.conectsol.solarsync.vistoria;

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
import com.conectsol.solarsync.vistoria.dto.ResultadoVistoriaRequest;
import com.conectsol.solarsync.vistoria.dto.SolicitarVistoriaRequest;
import com.conectsol.solarsync.vistoria.dto.VistoriaFiltro;
import com.conectsol.solarsync.vistoria.dto.VistoriaResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/vistorias")
@RequiredArgsConstructor
@Tag(name = "Vistorias", description = "Etapa 4: vistoria da Coelba após a instalação")
public class VistoriaController {

    private final VistoriaService vistoriaService;
    private final HistoricoStatusService historicoStatusService;

    @GetMapping
    @PodeLer
    @Operation(summary = "Lista vistorias paginadas, com filtros combináveis")
    public PaginaResponse<VistoriaResponse> listar(
            @Valid VistoriaFiltro filtro,
            @PageableDefault(size = 20, sort = "dataSolicitacao", direction = Sort.Direction.DESC)
            Pageable paginacao) {

        return PaginaResponse.de(vistoriaService.listar(filtro, paginacao), VistoriaResponse::de);
    }

    @GetMapping("/{id}")
    @PodeLer
    public VistoriaResponse buscar(@PathVariable Long id) {
        return VistoriaResponse.de(vistoriaService.buscar(id));
    }

    @GetMapping("/{id}/historico")
    @PodeLer
    @Operation(summary = "Linha do tempo da vistoria, incluindo as idas e vindas de reprova")
    public List<HistoricoStatusResponse> historico(@PathVariable Long id) {
        return historicoStatusService.listar(EntidadeTipo.VISTORIA, id);
    }

    @PostMapping
    @PodeEscrever
    @Operation(
            summary = "Solicita a vistoria de um projeto instalado",
            description = "Exige que o projeto tenha data de instalação registrada — vistoria é "
                    + "pós-instalação. Para achar a fila, use "
                    + "GET /api/projetos?instalado=true&semVistoria=true.")
    @ApiResponse(responseCode = "409",
            description = "Projeto sem data de instalação registrada")
    public ResponseEntity<VistoriaResponse> solicitar(
            @RequestBody @Valid SolicitarVistoriaRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        Vistoria criada = vistoriaService.solicitar(
                requisicao.projetoId(), requisicao.dataSolicitacao(), usuario.id());
        return ResponseEntity.created(URI.create("/api/vistorias/" + criada.getId()))
                .body(VistoriaResponse.de(criada));
    }

    @PostMapping("/{id}/aprovar")
    @PodeEscrever
    @Operation(
            summary = "Registra vistoria aprovada",
            description = "Fecha o ciclo do cliente: daqui o processo segue para o pós-venda.")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public VistoriaResponse aprovar(@PathVariable Long id,
            @RequestBody(required = false) @Valid ResultadoVistoriaRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return VistoriaResponse.de(vistoriaService.aprovar(id,
                requisicao == null ? null : requisicao.dataResultado(), usuario.id()));
    }

    @PostMapping("/{id}/reprovar")
    @PodeEscrever
    @Operation(summary = "Registra vistoria reprovada")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public VistoriaResponse reprovar(@PathVariable Long id,
            @RequestBody(required = false) @Valid ResultadoVistoriaRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return VistoriaResponse.de(vistoriaService.reprovar(id,
                requisicao == null ? null : requisicao.dataResultado(), usuario.id()));
    }

    @PostMapping("/{id}/resolicitar")
    @PodeEscrever
    @Operation(
            summary = "Pede a vistoria de novo após uma reprova",
            description = "Reutiliza o mesmo registro, para o histórico mostrar quantas idas e "
                    + "vindas o cliente teve.")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public VistoriaResponse resolicitar(@PathVariable Long id,
            @RequestBody(required = false) @Valid SolicitarVistoriaRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return VistoriaResponse.de(vistoriaService.resolicitar(id,
                requisicao == null ? null : requisicao.dataSolicitacao(), usuario.id()));
    }

    @DeleteMapping("/{id}")
    @SomenteAdministrador
    @Operation(summary = "Exclui a vistoria", description = "Restrito a ADMINISTRADOR.")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        vistoriaService.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
