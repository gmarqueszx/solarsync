package com.conectsol.solarsync.cliente;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conectsol.solarsync.cliente.dto.ClienteFiltro;
import com.conectsol.solarsync.cliente.dto.ClienteRequest;
import com.conectsol.solarsync.cliente.dto.ClienteResponse;
import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.common.security.Autenticado;
import com.conectsol.solarsync.common.security.PodeEscrever;
import com.conectsol.solarsync.common.security.PodeLer;
import com.conectsol.solarsync.common.security.SomenteAdministrador;
import com.conectsol.solarsync.common.security.UsuarioAutenticado;
import com.conectsol.solarsync.common.web.PaginaResponse;
import com.conectsol.solarsync.historico.HistoricoStatusService;
import com.conectsol.solarsync.historico.dto.HistoricoStatusResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/clientes")
@RequiredArgsConstructor
@Tag(name = "Clientes", description = "Cadastro base do fluxo de homologação")
public class ClienteController {

    private final ClienteService clienteService;
    private final HistoricoStatusService historicoStatusService;

    @GetMapping
    @PodeLer
    @Operation(
            summary = "Lista clientes paginados, com busca e filtros de fila",
            description = "`nome` casa nome ou UC Coelba. "
                    + "`statusTriagem=AGUARDANDO_VERIFICACAO` é a fila de quem ninguém checou "
                    + "na Coelba ainda; `semConsultaDebito=true` é a fila da consulta de "
                    + "débito, que precede tanto a resolução da pendência quanto o projeto.")
    public PaginaResponse<ClienteResponse> listar(
            @Valid ClienteFiltro filtro,
            @PageableDefault(size = 20, sort = "nome", direction = Sort.Direction.ASC)
            Pageable paginacao) {
        return PaginaResponse.de(clienteService.listar(filtro, paginacao), ClienteResponse::de);
    }

    @GetMapping("/aguardando-verificacao/quantidade")
    @PodeLer
    @Operation(summary = "Tamanho da fila de triagem, sem paginar a lista")
    public long quantidadeAguardandoVerificacao() {
        return clienteService.quantidadeAguardandoVerificacao();
    }

    @GetMapping("/{id}")
    @PodeLer
    public ClienteResponse buscar(@PathVariable Long id) {
        return clienteService.buscar(id);
    }

    @PostMapping
    @PodeEscrever
    @Operation(summary = "Cadastra um cliente")
    public ResponseEntity<ClienteResponse> criar(@RequestBody @Valid ClienteRequest requisicao) {
        ClienteResponse criado = clienteService.criar(requisicao);
        return ResponseEntity.created(URI.create("/api/clientes/" + criado.id())).body(criado);
    }

    @PutMapping("/{id}")
    @PodeEscrever
    public ClienteResponse atualizar(@PathVariable Long id,
            @RequestBody @Valid ClienteRequest requisicao) {
        return clienteService.atualizar(id, requisicao);
    }

    @PostMapping("/{id}/sem-pendencia")
    @PodeEscrever
    @Operation(
            summary = "Registra que a Coelba foi checada e o cliente não tem pendência",
            description = "Etapa 1 do fluxo pelo caminho \"segue direto\". Cria o Projeto do "
                    + "cliente em RECEBIDO e o coloca na fila de consulta de débito. Se já "
                    + "houver projeto em andamento, nenhum novo é criado.")
    public ClienteResponse marcarSemPendencia(@PathVariable Long id,
            @Autenticado UsuarioAutenticado usuario) {
        return clienteService.marcarSemPendencia(id, usuario.id());
    }

    @PostMapping("/{id}/reverificar")
    @PodeEscrever
    @Operation(
            summary = "Devolve o cliente para a fila de verificação de pendência",
            description = "Para o novo ciclo (uma ampliação meses depois) ou para desfazer "
                    + "marcação errada. Não apaga o projeto já criado.")
    public ClienteResponse reverificar(@PathVariable Long id,
            @Autenticado UsuarioAutenticado usuario) {
        return clienteService.reverificar(id, usuario.id());
    }

    @GetMapping("/{id}/historico")
    @PodeLer
    @Operation(summary = "Linha do tempo da triagem de pendência do cliente")
    public List<HistoricoStatusResponse> historico(@PathVariable Long id) {
        return historicoStatusService.listar(EntidadeTipo.CLIENTE, id);
    }

    @DeleteMapping("/{id}")
    @SomenteAdministrador
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        clienteService.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
