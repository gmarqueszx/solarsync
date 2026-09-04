package com.conectsol.solarsync.unificacao;

import java.net.URI;

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

import com.conectsol.solarsync.common.security.Autenticado;
import com.conectsol.solarsync.common.security.PodeEscrever;
import com.conectsol.solarsync.common.security.PodeLer;
import com.conectsol.solarsync.common.security.SomenteAdministrador;
import com.conectsol.solarsync.common.security.UsuarioAutenticado;
import com.conectsol.solarsync.common.web.PaginaResponse;
import com.conectsol.solarsync.unificacao.dto.DataDesligamentoRequest;
import com.conectsol.solarsync.unificacao.dto.UnificacaoFiltro;
import com.conectsol.solarsync.unificacao.dto.UnificacaoRequest;
import com.conectsol.solarsync.unificacao.dto.UnificacaoResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/unificacoes")
@RequiredArgsConstructor
@Tag(name = "Unificações", description = "Etapa 4: unificação e desligamento do medidor antigo")
public class UnificacaoController {

    private final UnificacaoService unificacaoService;

    @GetMapping
    @PodeLer
    @Operation(
            summary = "Lista unificações paginadas, com filtros combináveis",
            description = "As filas da etapa 4: feita=false é o que falta unificar; "
                    + "feita=true com desligamentoStatus=NAO_SOLICITADO é quem já unificou e "
                    + "ainda precisa pedir o desligamento; e desligamentoStatus=SOLICITADO "
                    + "mostra o que está aguardando retorno da equipe de campo.")
    public PaginaResponse<UnificacaoResponse> listar(
            @Valid UnificacaoFiltro filtro,
            @PageableDefault(size = 20, sort = "criadoEm", direction = Sort.Direction.DESC)
            Pageable paginacao) {

        return PaginaResponse.de(
                unificacaoService.listar(filtro, paginacao), UnificacaoResponse::de);
    }

    @GetMapping("/{id}")
    @PodeLer
    public UnificacaoResponse buscar(@PathVariable Long id) {
        return UnificacaoResponse.de(unificacaoService.buscar(id));
    }

    @PostMapping
    @PodeEscrever
    @Operation(
            summary = "Registra uma unificação pendente",
            description = "Sem cidade no corpo, herda a cidade do cliente.")
    public ResponseEntity<UnificacaoResponse> criar(
            @RequestBody @Valid UnificacaoRequest requisicao) {

        UnificacaoResponse criada = UnificacaoResponse.de(unificacaoService.criar(requisicao));
        return ResponseEntity.created(URI.create("/api/unificacoes/" + criada.id())).body(criada);
    }

    @PutMapping("/{id}")
    @PodeEscrever
    @Operation(
            summary = "Edita os dados da unificação",
            description = "Não altera os marcos: use as ações de concluir e desligar.")
    public UnificacaoResponse atualizar(@PathVariable Long id,
            @RequestBody @Valid UnificacaoRequest requisicao) {
        return UnificacaoResponse.de(unificacaoService.atualizar(id, requisicao));
    }

    @PostMapping("/{id}/concluir")
    @PodeEscrever
    @Operation(summary = "Marca a unificação como feita")
    public UnificacaoResponse concluir(@PathVariable Long id) {
        return UnificacaoResponse.de(unificacaoService.marcarFeita(id, true));
    }

    @PostMapping("/{id}/reabrir")
    @PodeEscrever
    @Operation(summary = "Desfaz a marcação de feita, para corrigir registro errado")
    public UnificacaoResponse reabrir(@PathVariable Long id) {
        return UnificacaoResponse.de(unificacaoService.marcarFeita(id, false));
    }

    @PostMapping("/{id}/solicitar-desligamento")
    @PodeEscrever
    @Operation(
            summary = "Solicita o desligamento do medidor unificado",
            description = "Exige a unificação confirmada (409 UNIFICACAO_NAO_FEITA caso não "
                    + "esteja): pedir antes desligaria um medidor de que o cliente ainda "
                    + "depende. Depois disso o caso fica aguardando o retorno da equipe de "
                    + "campo — use o filtro desligamentoStatus=SOLICITADO para ver essa fila.")
    @ApiResponse(responseCode = "409", description = "Unificação não concluída, ou transição "
            + "inválida a partir do status atual")
    public UnificacaoResponse solicitarDesligamento(@PathVariable Long id,
            @RequestBody(required = false) @Valid DataDesligamentoRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return UnificacaoResponse.de(unificacaoService.solicitarDesligamento(
                id, requisicao == null ? null : requisicao.data(), usuario.id()));
    }

    @PostMapping("/{id}/abrir-os")
    @PodeEscrever
    @Operation(
            summary = "Registra que foi aberta O.S. para o desligamento",
            description = "Caminho para quando a equipe de campo não realiza o desligamento. "
                    + "Sem isso o caso ficaria na fila de 'aguardando' indefinidamente, sem "
                    + "distinguir quem só espera de quem já precisou de ordem de serviço.")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public UnificacaoResponse abrirOrdemDeServico(@PathVariable Long id,
            @Autenticado UsuarioAutenticado usuario) {
        return UnificacaoResponse.de(unificacaoService.abrirOrdemDeServico(id, usuario.id()));
    }

    @PostMapping("/{id}/concluir-desligamento")
    @PodeEscrever
    @Operation(summary = "Registra que o medidor unificado foi desligado")
    @ApiResponse(responseCode = "409", description = "Transição inválida a partir do status atual")
    public UnificacaoResponse concluirDesligamento(@PathVariable Long id,
            @RequestBody(required = false) @Valid DataDesligamentoRequest requisicao,
            @Autenticado UsuarioAutenticado usuario) {

        return UnificacaoResponse.de(unificacaoService.concluirDesligamento(
                id, requisicao == null ? null : requisicao.data(), usuario.id()));
    }

    @DeleteMapping("/{id}")
    @SomenteAdministrador
    @Operation(summary = "Exclui a unificação", description = "Restrito a ADMINISTRADOR.")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        unificacaoService.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
