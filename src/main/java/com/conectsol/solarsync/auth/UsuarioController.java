package com.conectsol.solarsync.auth;

import java.net.URI;
import java.util.List;

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

import com.conectsol.solarsync.auth.dto.DefinirSenhaRequest;
import com.conectsol.solarsync.auth.dto.UsuarioRequest;
import com.conectsol.solarsync.auth.dto.UsuarioResponse;
import com.conectsol.solarsync.auth.dto.UsuarioResumoResponse;
import com.conectsol.solarsync.common.security.PodeLer;
import com.conectsol.solarsync.common.security.SomenteAdministrador;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/usuarios")
@RequiredArgsConstructor
@Tag(name = "Usuários", description = "Gestão de acesso — restrita a ADMINISTRADOR")
public class UsuarioController {

    private final UsuarioService usuarioService;

    /** Liberado a todos os papéis: alimenta o seletor de responsável/analista no frontend. */
    @GetMapping("/lookup")
    @PodeLer
    @Operation(summary = "Lista id e nome dos usuários, para seletores")
    public List<UsuarioResumoResponse> lookup(
            @RequestParam(defaultValue = "true") boolean ativo) {
        return usuarioService.lookup(ativo);
    }

    @GetMapping
    @SomenteAdministrador
    @Operation(summary = "Lista os usuários com papéis e situação")
    public List<UsuarioResponse> listar() {
        return usuarioService.listar();
    }

    @GetMapping("/{id}")
    @SomenteAdministrador
    public UsuarioResponse buscar(@PathVariable Long id) {
        return usuarioService.buscar(id);
    }

    @PostMapping
    @SomenteAdministrador
    @Operation(
            summary = "Cadastra um usuário",
            description = "Sem senha, o usuário entra apenas pelo Google. Cadastro prévio é "
                    + "obrigatório: o login Google não cria conta.")
    @ApiResponse(responseCode = "409", description = "E-mail já cadastrado")
    public ResponseEntity<UsuarioResponse> criar(@RequestBody @Valid UsuarioRequest requisicao) {
        UsuarioResponse criado = usuarioService.criar(requisicao);
        return ResponseEntity.created(URI.create("/api/usuarios/" + criado.id())).body(criado);
    }

    @PutMapping("/{id}")
    @SomenteAdministrador
    public UsuarioResponse atualizar(@PathVariable Long id,
            @RequestBody @Valid UsuarioRequest requisicao) {
        return usuarioService.atualizar(id, requisicao);
    }

    @PostMapping("/{id}/senha")
    @SomenteAdministrador
    @Operation(summary = "Define ou redefine a senha do usuário")
    public UsuarioResponse definirSenha(@PathVariable Long id,
            @RequestBody @Valid DefinirSenhaRequest requisicao) {
        return usuarioService.definirSenha(id, requisicao.senha());
    }

    @PostMapping("/{id}/ativar")
    @SomenteAdministrador
    public UsuarioResponse ativar(@PathVariable Long id) {
        return usuarioService.alterarAtivacao(id, true);
    }

    @PostMapping("/{id}/desativar")
    @SomenteAdministrador
    @Operation(
            summary = "Desativa o usuário",
            description = "É a forma correta de cortar acesso de quem saiu: o access token "
                    + "expira em minutos e a renovação passa a ser negada, preservando a "
                    + "auditoria que a exclusão destruiria.")
    public UsuarioResponse desativar(@PathVariable Long id) {
        return usuarioService.alterarAtivacao(id, false);
    }

    @DeleteMapping("/{id}")
    @SomenteAdministrador
    @ApiResponse(responseCode = "409",
            description = "Usuário já referenciado no histórico; desative em vez de excluir")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        usuarioService.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
