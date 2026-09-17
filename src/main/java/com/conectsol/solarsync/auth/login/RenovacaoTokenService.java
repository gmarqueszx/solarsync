package com.conectsol.solarsync.auth.login;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.auth.dto.TokenResponse;
import com.conectsol.solarsync.auth.jwt.TokenService;
import com.conectsol.solarsync.common.exception.UsuarioNaoAutorizadoException;

import lombok.RequiredArgsConstructor;

/**
 * Rotação de tokens. Reler o usuário no banco a cada renovação é o que faz
 * {@code usuario.ativo = false} cortar o acesso de quem saiu da empresa: o access token morre
 * em minutos e a renovação é negada — sem precisar de tabela de sessão ou lista de revogação.
 */
@Service
@RequiredArgsConstructor
public class RenovacaoTokenService {

    private static final Logger log = LoggerFactory.getLogger(RenovacaoTokenService.class);

    private final TokenService tokenService;
    private final UsuarioRepository usuarioRepository;

    @Transactional(readOnly = true)
    public TokenResponse renovar(String refreshToken) {
        Long usuarioId = tokenService.idDoUsuarioNoRefresh(refreshToken);

        Usuario usuario = usuarioRepository.findById(usuarioId)
                .filter(Usuario::isAtivo)
                .orElseThrow(() -> {
                    log.warn("Renovação recusada: usuário {} inexistente ou inativo", usuarioId);
                    return new UsuarioNaoAutorizadoException();
                });

        return TokenResponse.de(tokenService.emitirPar(usuario), usuario);
    }
}
