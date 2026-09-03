package com.conectsol.solarsync.projeto;

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
import com.conectsol.solarsync.projeto.dto.CorrigirStatusRequest;
import com.conectsol.solarsync.projeto.dto.ProjetoAprovarRequest;
import com.conectsol.solarsync.projeto.dto.ProjetoAtualizarRequest;
import com.conectsol.solarsync.projeto.dto.ProjetoCriarRequest;
import com.conectsol.solarsync.projeto.dto.ProjetoEncaminharRequest;
import com.conectsol.solarsync.projeto.dto.ProjetoFiltro;
import com.conectsol.solarsync.projeto.dto.ProjetoResponse;
import com.conectsol.solarsync.projeto.dto.ProjetoResumoResponse;
import com.conectsol.solarsync.projeto.dto.RegistrarInstalacaoRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Adaptador HTTP das etapas 2 e 3 do fluxo. Como em Pendências, transições são endpoints de
 * ação e o PUT não aceita status.
 */
@RestController
@RequestMapping("/api/projetos")
@RequiredArgsConstructor
@Tag(name = "Projetos", description = "Etapas 2 e 3: homologação e acompanhamento na Coelba")
public class ProjetoController {

    private final ProjetoService projetoService;
    private final HistoricoStatusService historicoStatusService;

    @GetMapping
    @PodeLer
    @Operation(summary = "Lista projetos paginados, com filtros combináveis")
    public PaginaResponse<ProjetoResumoResponse> listar(
            @Valid ProjetoFiltro filtro,
            @PageableDefault(size = 20, sort = "dataRecebimento", direction = Sort.Direction.DESC)
            Pageable paginacao) {

        return PaginaResponse.de(
                projetoService.listar(filtro, paginacao), ProjetoResumoResponse::de);
    }

    @GetMapping("/{id}")
    @PodeLer
    public ProjetoResponse buscar(@PathVariable Long id) {
        return ProjetoResponse.de(projetoService.buscar(id));
    }

    @GetMapping("/{id}/historico")
    @PodeLer
    @Operation(summary = "Linha do tempo das mudanças de status do projeto")
    public List<HistoricoStatusResponse> historico(@PathVariable Long id) {
        return historicoStatusService.listar(EntidadeTipo.PROJETO, id);
    }

    @PostMapping
    @PodeEscrever
    @Operation(
            summary = "Cria um projeto em RECEBIDO",
            description = "Para o caso em que o projeto não veio da resolução de uma pendência.")
    public ResponseEntity<ProjetoResponse> criar(
            @RequestBody @Valid ProjetoCriarRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        Projeto criado = projetoService.criar(requisicao, usuario.id());
        return ResponseEntity.created(URI.create("/api/projetos/" + criado.getId()))
                .body(ProjetoResponse.de(criado));
    }

    @PutMapping("/{id}")
    @PodeEscrever
    @Operation(
            summary = "Edita os dados do projeto",
            description = "Não altera status: use os endpoints de ação.")
    public ProjetoResponse atualizar(@PathVariable Long id,
            @RequestBody @Valid ProjetoAtualizarRequest requisicao) {
        return ProjetoResponse.de(projetoService.atualizar(id, requisicao));
    }

    @DeleteMapping("/{id}")
    @SomenteAdministrador
    @Operation(summary = "Exclui o projeto", description = "Restrito a ADMINISTRADOR.")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        projetoService.excluir(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/aguardar-envio")
    @PodeEscrever
    @Operation(
            summary = "Marca o projeto como AGUARDANDO_ENVIO",
            description = "Projeto pronto mas com o envio bloqueado, tipicamente por débito do "
                    + "cliente.")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public ProjetoResponse aguardarEnvio(@PathVariable Long id,
            @Autenticado UsuarioAutenticado usuario) {
        return ProjetoResponse.de(projetoService.aguardarEnvio(id, usuario.id()));
    }

    @PostMapping("/{id}/encaminhar")
    @PodeEscrever
    @Operation(
            summary = "Encaminha o projeto à Coelba",
            description = "Sem dataEncaminhado no corpo, assume hoje. Essa data alimenta a "
                    + "métrica de tempo entre recebimento e envio.")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public ProjetoResponse encaminhar(@PathVariable Long id,
            @RequestBody(required = false) @Valid ProjetoEncaminharRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return ProjetoResponse.de(projetoService.encaminhar(id,
                requisicao == null ? null : requisicao.dataArt(),
                requisicao == null ? null : requisicao.dataEncaminhado(),
                usuario.id()));
    }

    @PostMapping("/{id}/reencaminhar")
    @PodeEscrever
    @Operation(summary = "Reenvia o projeto após correção de uma reprova")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public ProjetoResponse reencaminhar(@PathVariable Long id,
            @RequestBody(required = false) @Valid ProjetoEncaminharRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return ProjetoResponse.de(projetoService.reencaminhar(id,
                requisicao == null ? null : requisicao.dataEncaminhado(), usuario.id()));
    }

    @PostMapping("/{id}/aprovar")
    @PodeEscrever
    @Operation(summary = "Registra a aprovação pela Coelba")
    @ApiResponse(responseCode = "409",
            description = "Transição inválida — por exemplo aprovar projeto que não foi enviado")
    public ProjetoResponse aprovar(@PathVariable Long id,
            @RequestBody(required = false) @Valid ProjetoAprovarRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return ProjetoResponse.de(projetoService.aprovar(id,
                requisicao == null ? null : requisicao.dataAprovacao(), usuario.id()));
    }

    @PostMapping("/{id}/reprovar")
    @PodeEscrever
    @Operation(summary = "Registra a reprova; o motivo é obrigatório")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public ProjetoResponse reprovar(@PathVariable Long id,
            @RequestBody @Valid MotivoRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return ProjetoResponse.de(
                projetoService.reprovar(id, requisicao.motivo(), usuario.id()));
    }

    @PostMapping("/{id}/registrar-instalacao")
    @PodeEscrever
    @Operation(
            summary = "Registra a data em que a usina foi instalada",
            description = "Entrada manual, a partir da informação que chega do campo. Não altera "
                    + "o status (que acompanha a homologação na Coelba) e não exige que o "
                    + "projeto esteja aprovado. É o marco de partida da vistoria: sem esta "
                    + "data, solicitar vistoria é bloqueado. Para achar a fila de trabalho, use "
                    + "GET /api/projetos?instalado=true&semVistoria=true.")
    public ProjetoResponse registrarInstalacao(@PathVariable Long id,
            @RequestBody @Valid RegistrarInstalacaoRequest requisicao) {
        return ProjetoResponse.de(
                projetoService.registrarInstalacao(id, requisicao.dataInstalacao()));
    }

    @PostMapping("/{id}/corrigir-status")
    @SomenteAdministrador
    @Operation(
            summary = "Corrige o status pulando a máquina de estados",
            description = "Restrito a ADMINISTRADOR e com justificativa obrigatória. A auditoria "
                    + "é mantida. Existe para a importação da planilha, em que os dados chegam "
                    + "em qualquer ordem, e para destravar erro operacional.")
    public ProjetoResponse corrigirStatus(@PathVariable Long id,
            @RequestBody @Valid CorrigirStatusRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return ProjetoResponse.de(projetoService.corrigirStatus(
                id, requisicao.novoStatus(), requisicao.justificativa(), usuario.id()));
    }
}
