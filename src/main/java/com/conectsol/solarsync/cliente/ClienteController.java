package com.conectsol.solarsync.cliente;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.conectsol.solarsync.cliente.dto.ClienteRequest;
import com.conectsol.solarsync.cliente.dto.ClienteResponse;
import com.conectsol.solarsync.common.security.PodeEscrever;
import com.conectsol.solarsync.common.security.PodeLer;
import com.conectsol.solarsync.common.security.SomenteAdministrador;
import com.conectsol.solarsync.common.web.PaginaResponse;

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

    @GetMapping
    @PodeLer
    @Operation(summary = "Lista clientes paginados, com busca por nome")
    public PaginaResponse<ClienteResponse> listar(
            @RequestParam(required = false) String nome,
            @PageableDefault(size = 20, sort = "nome", direction = Sort.Direction.ASC)
            Pageable paginacao) {
        return PaginaResponse.de(clienteService.listar(nome, paginacao), ClienteResponse::de);
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

    @DeleteMapping("/{id}")
    @SomenteAdministrador
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        clienteService.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
