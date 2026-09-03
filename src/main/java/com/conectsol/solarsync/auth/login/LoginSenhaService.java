package com.conectsol.solarsync.auth.login;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.auth.dto.TokenResponse;
import com.conectsol.solarsync.auth.jwt.TokenService;
import com.conectsol.solarsync.common.exception.CredenciaisInvalidasException;

/**
 * Login por e-mail e senha.
 * <p>
 * Todos os motivos de falha — usuário inexistente, sem senha cadastrada (só entra pelo
 * Google), inativo, ou senha errada — devolvem a <b>mesma</b> exceção genérica. Diferenciar
 * permitiria a um atacante enumerar quem tem conta. O motivo real vai para o log.
 */
@Service
public class LoginSenhaService {

    private static final Logger log = LoggerFactory.getLogger(LoginSenhaService.class);

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    /** Hash descartável, para gastar o mesmo tempo quando o usuário não existe. */
    private final String hashDeComparacaoFalsa;

    public LoginSenhaService(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder,
            TokenService tokenService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.hashDeComparacaoFalsa = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public TokenResponse autenticar(String email, String senha) {
        String emailNormalizado = Usuario.normalizarEmail(email);
        Optional<Usuario> encontrado = usuarioRepository.findByEmail(emailNormalizado);

        if (encontrado.isEmpty()) {
            // Compara contra um hash qualquer para não vazar por tempo de resposta que o
            // e-mail não existe.
            passwordEncoder.matches(senha, hashDeComparacaoFalsa);
            log.warn("Login recusado: e-mail não cadastrado ({})", emailNormalizado);
            throw new CredenciaisInvalidasException();
        }

        Usuario usuario = encontrado.get();
        if (!usuario.isAtivo()) {
            log.warn("Login recusado: usuário inativo ({})", emailNormalizado);
            throw new CredenciaisInvalidasException();
        }
        if (usuario.getSenhaHash() == null) {
            log.warn("Login recusado: usuário sem senha cadastrada, entra só pelo Google ({})",
                    emailNormalizado);
            throw new CredenciaisInvalidasException();
        }
        if (!passwordEncoder.matches(senha, usuario.getSenhaHash())) {
            log.warn("Login recusado: senha incorreta ({})", emailNormalizado);
            throw new CredenciaisInvalidasException();
        }

        return TokenResponse.de(tokenService.emitirPar(usuario), usuario);
    }
}
