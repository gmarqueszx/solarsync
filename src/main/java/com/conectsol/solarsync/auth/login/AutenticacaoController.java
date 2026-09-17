package com.conectsol.solarsync.auth.login;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conectsol.solarsync.auth.dto.LoginSenhaRequest;
import com.conectsol.solarsync.auth.dto.RefreshRequest;
import com.conectsol.solarsync.auth.dto.TokenResponse;
import com.conectsol.solarsync.auth.dto.UsuarioLogadoResponse;
import com.conectsol.solarsync.common.security.Autenticado;
import com.conectsol.solarsync.common.security.UsuarioAutenticado;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Login por e-mail e senha, e renovação de token.
 * <p>
 * <b>Não há auto-cadastro nem login federado</b> (decisão do usuário em 16/09/2026, que removeu
 * o login Google): entrar no sistema exige uma conta criada por ADMINISTRADOR ou GESTOR em
 * {@code POST /api/usuarios}, com senha definida no cadastro.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Autenticação", description = "Login por e-mail e senha, e renovação de token")
public class AutenticacaoController {

    private final LoginSenhaService loginSenhaService;
    private final RenovacaoTokenService renovacaoTokenService;

    @PostMapping("/login")
    @Operation(summary = "Login com e-mail e senha")
    @ApiResponse(responseCode = "200", description = "Tokens emitidos")
    @ApiResponse(responseCode = "401", description = "Credenciais inválidas")
    public TokenResponse login(@RequestBody @Valid LoginSenhaRequest requisicao) {
        return loginSenhaService.autenticar(requisicao.email(), requisicao.senha());
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Troca o refresh token por um novo par de tokens",
            description = "O usuário é relido no banco: quem foi desativado não renova. Não há "
                    + "logout no servidor — a API é stateless, o cliente descarta os tokens.")
    @ApiResponse(responseCode = "401", description = "Refresh token inválido, expirado ou de "
            + "usuário inativo")
    public TokenResponse renovar(@RequestBody @Valid RefreshRequest requisicao) {
        return renovacaoTokenService.renovar(requisicao.refreshToken());
    }

    @GetMapping("/eu")
    @Operation(summary = "Dados e papéis do usuário autenticado")
    public UsuarioLogadoResponse eu(@Autenticado UsuarioAutenticado usuario) {
        return UsuarioLogadoResponse.de(usuario);
    }
}
