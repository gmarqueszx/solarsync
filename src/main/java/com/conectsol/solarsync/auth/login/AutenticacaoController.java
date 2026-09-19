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
import com.conectsol.solarsync.common.exception.CredenciaisInvalidasException;
import com.conectsol.solarsync.common.security.Autenticado;
import com.conectsol.solarsync.common.security.UsuarioAutenticado;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ControleDeTentativasDeLogin controleDeTentativas;

    /**
     * O limite de tentativas é aplicado <b>aqui</b>, e não dentro do
     * {@link LoginSenhaService}, ao contrário das outras guardas do projeto (que moram no
     * service para valerem em qualquer origem). O motivo é que esta não é regra de negócio: ela
     * se apoia no IP de quem chamou, que é informação de transporte e não existe fora do HTTP.
     * O service segue sendo o único a decidir se a senha confere.
     * <p>
     * A ordem importa: verificar → autenticar → contar. Recusar antes de chamar o service é o que
     * poupa o BCrypt, que é o recurso que o limite existe para proteger.
     */
    @PostMapping("/login")
    @Operation(summary = "Login com e-mail e senha")
    @ApiResponse(responseCode = "200", description = "Tokens emitidos")
    @ApiResponse(responseCode = "401", description = "Credenciais inválidas")
    @ApiResponse(responseCode = "429", description = "Tentativas demais; veja Retry-After")
    public TokenResponse login(@RequestBody @Valid LoginSenhaRequest requisicao,
            HttpServletRequest http) {

        String origem = controleDeTentativas.origemDe(http);
        controleDeTentativas.verificar(origem, requisicao.email());

        try {
            TokenResponse resposta =
                    loginSenhaService.autenticar(requisicao.email(), requisicao.senha());
            controleDeTentativas.acertou(requisicao.email());
            return resposta;
        } catch (CredenciaisInvalidasException recusado) {
            controleDeTentativas.falhou(origem, requisicao.email());
            throw recusado;
        }
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
