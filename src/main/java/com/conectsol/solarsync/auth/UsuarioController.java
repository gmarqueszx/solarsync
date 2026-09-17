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
import com.conectsol.solarsync.auth.dto.UsuarioAtualizarRequest;
import com.conectsol.solarsync.auth.dto.UsuarioCriarRequest;
import com.conectsol.solarsync.auth.dto.UsuarioResponse;
import com.conectsol.solarsync.auth.dto.UsuarioResumoResponse;
import com.conectsol.solarsync.common.security.Autenticado;
import com.conectsol.solarsync.common.security.GerenciaUsuarios;
import com.conectsol.solarsync.common.security.PodeLer;
import com.conectsol.solarsync.common.security.SomenteAdministrador;
import com.conectsol.solarsync.common.security.UsuarioAutenticado;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/usuarios")
@RequiredArgsConstructor
@Tag(name = "Usuários", description = "Gestão de acesso — restrita a ADMINISTRADOR e GESTOR")
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
    @GerenciaUsuarios
    @Operation(summary = "Lista os usuários com papéis e situação")
    public List<UsuarioResponse> listar() {
        return usuarioService.listar();
    }

    @GetMapping("/{id}")
    @GerenciaUsuarios
    public UsuarioResponse buscar(@PathVariable Long id) {
        return usuarioService.buscar(id);
    }

    @PostMapping
    @GerenciaUsuarios
    @Operation(
            summary = "Cadastra um usuário",
            description = "Único caminho de entrada de gente no sistema: não há auto-cadastro. "
                    + "A senha é obrigatória e provisória — quem recebe a conta troca em "
                    + "POST /api/usuarios/{id}/senha. Só um ADMINISTRADOR concede o papel "
                    + "ADMINISTRADOR.")
    @ApiResponse(responseCode = "409", description = "E-mail já cadastrado")
    @ApiResponse(responseCode = "403", description = "GESTOR tentando criar um ADMINISTRADOR")
    public ResponseEntity<UsuarioResponse> criar(
            @RequestBody @Valid UsuarioCriarRequest requisicao,
            @Autenticado UsuarioAutenticado autor) {

        UsuarioResponse criado = usuarioService.criar(requisicao, autor);
        return ResponseEntity.created(URI.create("/api/usuarios/" + criado.id())).body(criado);
    }

    @PutMapping("/{id}")
    @GerenciaUsuarios
    @Operation(summary = "Edita nome, e-mail e papéis; a senha tem endpoint próprio")
    @ApiResponse(responseCode = "403", description = "GESTOR tentando alterar um ADMINISTRADOR")
    public UsuarioResponse atualizar(@PathVariable Long id,
            @RequestBody @Valid UsuarioAtualizarRequest requisicao,
            @Autenticado UsuarioAutenticado autor) {

        return usuarioService.atualizar(id, requisicao, autor);
    }

    @PostMapping("/{id}/senha")
    @GerenciaUsuarios
    @Operation(summary = "Define ou redefine a senha do usuário")
    public UsuarioResponse definirSenha(@PathVariable Long id,
            @RequestBody @Valid DefinirSenhaRequest requisicao,
            @Autenticado UsuarioAutenticado autor) {

        return usuarioService.definirSenha(id, requisicao.senha(), autor);
    }

    @PostMapping("/{id}/ativar")
    @GerenciaUsuarios
    public UsuarioResponse ativar(@PathVariable Long id,
            @Autenticado UsuarioAutenticado autor) {
        return usuarioService.alterarAtivacao(id, true, autor);
    }

    @PostMapping("/{id}/desativar")
    @GerenciaUsuarios
    @Operation(
            summary = "Desativa o usuário",
            description = "É a forma correta de cortar acesso de quem saiu: o access token "
                    + "expira em minutos e a renovação passa a ser negada, preservando a "
                    + "auditoria que a exclusão destruiria.")
    public UsuarioResponse desativar(@PathVariable Long id,
            @Autenticado UsuarioAutenticado autor) {
        return usuarioService.alterarAtivacao(id, false, autor);
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
