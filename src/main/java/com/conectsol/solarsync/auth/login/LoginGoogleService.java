package com.conectsol.solarsync.auth.login;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.auth.dto.TokenResponse;
import com.conectsol.solarsync.auth.google.GoogleProperties;
import com.conectsol.solarsync.auth.google.VerificadorIdTokenGoogle;
import com.conectsol.solarsync.auth.google.VerificadorIdTokenGoogle.IdentidadeGoogle;
import com.conectsol.solarsync.auth.jwt.TokenService;
import com.conectsol.solarsync.common.exception.UsuarioNaoAutorizadoException;

import lombok.RequiredArgsConstructor;

/**
 * Login com Google. <b>Não faz auto-cadastro</b>: entrar exige que o e-mail já exista em
 * {@code usuario} com {@code ativo = true}. Três barreiras, nesta ordem:
 * <ol>
 *   <li>criptográfica — assinatura, emissor, audiência e validade do ID token
 *       ({@link VerificadorIdTokenGoogle});</li>
 *   <li>domínio — {@code email_verified} e domínio na allowlist;</li>
 *   <li>cadastro prévio — usuário existente e ativo.</li>
 * </ol>
 * Esta classe não chama {@code usuarioRepository.save} em nenhum caminho, e há teste
 * afirmando isso: é a garantia mecânica de que este fluxo não cria conta.
 * <p>
 * Todas as falhas devolvem a mesma exceção genérica, para não revelar qual barreira caiu.
 */
@Service
@RequiredArgsConstructor
public class LoginGoogleService {

    private static final Logger log = LoggerFactory.getLogger(LoginGoogleService.class);

    private final VerificadorIdTokenGoogle verificador;
    private final GoogleProperties propriedades;
    private final UsuarioRepository usuarioRepository;
    private final TokenService tokenService;

    @Transactional(readOnly = true)
    public TokenResponse autenticar(String idToken) {
        IdentidadeGoogle identidade = verificador.verificar(idToken);

        if (!identidade.emailVerificado()) {
            log.warn("Login Google recusado: e-mail não verificado ({})", identidade.email());
            throw new UsuarioNaoAutorizadoException();
        }

        String email = Usuario.normalizarEmail(identidade.email());
        if (email == null || !dominioPermitido(email, identidade.hostedDomain())) {
            log.warn("Login Google recusado: domínio não permitido ({})", email);
            throw new UsuarioNaoAutorizadoException();
        }

        Usuario usuario = usuarioRepository.findByEmail(email)
                .filter(Usuario::isAtivo)
                .orElseThrow(() -> {
                    log.warn("Login Google recusado: e-mail sem usuário ativo cadastrado ({})",
                            email);
                    return new UsuarioNaoAutorizadoException();
                });

        return TokenResponse.de(tokenService.emitirPar(usuario), usuario);
    }

    private boolean dominioPermitido(String email, String hostedDomain) {
        int arroba = email.lastIndexOf('@');
        if (arroba < 0) {
            return false;
        }
        String dominio = email.substring(arroba + 1);
        boolean dominioDoEmailOk = propriedades.dominiosPermitidos().contains(dominio);

        // Quando o Google informa o hosted domain do Workspace, ele também precisa conferir.
        boolean hostedDomainOk = hostedDomain == null
                || propriedades.dominiosPermitidos().contains(hostedDomain);

        return dominioDoEmailOk && hostedDomainOk;
    }
}
