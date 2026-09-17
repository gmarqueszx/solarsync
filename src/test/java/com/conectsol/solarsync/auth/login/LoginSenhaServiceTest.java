package com.conectsol.solarsync.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.conectsol.solarsync.auth.NomePapel;
import com.conectsol.solarsync.auth.Papel;
import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.auth.jwt.TokenService;
import com.conectsol.solarsync.auth.jwt.TokenService.ParDeTokens;
import com.conectsol.solarsync.common.exception.CredenciaisInvalidasException;

/**
 * Todos os motivos de recusa devem produzir a <b>mesma</b> exceção e mensagem: diferenciar
 * permitiria a um atacante descobrir quais e-mails têm conta.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginSenhaServiceTest {

    private static final String SENHA_CORRETA = "senha-forte-123";

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private TokenService tokenService;

    private BCryptPasswordEncoder encoder;
    private LoginSenhaService servico;

    @BeforeEach
    void montarServico() {
        encoder = new BCryptPasswordEncoder();
        servico = new LoginSenhaService(usuarioRepository, encoder, tokenService);
    }

    private Usuario usuarioComSenha() {
        Usuario usuario = Usuario.builder()
                .nome("Larissa Analista")
                .email("larissa@conectsol.com")
                .ativo(true)
                .senhaHash(encoder.encode(SENHA_CORRETA))
                .papeis(Set.of(Papel.builder().nome(NomePapel.ANALISTA).build()))
                .build();
        usuario.setId(3L);
        return usuario;
    }

    @Test
    void emiteTokenComSenhaCorreta() {
        Usuario usuario = usuarioComSenha();
        when(usuarioRepository.findByEmail("larissa@conectsol.com"))
                .thenReturn(Optional.of(usuario));
        when(tokenService.emitirPar(usuario))
                .thenReturn(new ParDeTokens("access", "refresh", 900));

        var resposta = servico.autenticar("larissa@conectsol.com", SENHA_CORRETA);

        assertThat(resposta.accessToken()).isEqualTo("access");
        assertThat(resposta.tipo()).isEqualTo("Bearer");
        assertThat(resposta.usuario().papeis()).containsExactly(NomePapel.ANALISTA);
    }

    @Test
    void normalizaEmailAntesDeBuscar() {
        Usuario usuario = usuarioComSenha();
        when(usuarioRepository.findByEmail("larissa@conectsol.com"))
                .thenReturn(Optional.of(usuario));
        when(tokenService.emitirPar(usuario))
                .thenReturn(new ParDeTokens("access", "refresh", 900));

        assertThat(servico.autenticar(" Larissa@ConectSol.com ", SENHA_CORRETA)).isNotNull();
    }

    @Test
    void recusaSenhaIncorreta() {
        when(usuarioRepository.findByEmail("larissa@conectsol.com"))
                .thenReturn(Optional.of(usuarioComSenha()));

        assertThatThrownBy(() -> servico.autenticar("larissa@conectsol.com", "errada"))
                .isInstanceOf(CredenciaisInvalidasException.class)
                .hasMessage("Credenciais inválidas");
    }

    @Test
    void recusaEmailInexistenteComAMesmaMensagem() {
        when(usuarioRepository.findByEmail("ninguem@conectsol.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.autenticar("ninguem@conectsol.com", SENHA_CORRETA))
                .isInstanceOf(CredenciaisInvalidasException.class)
                .hasMessage("Credenciais inválidas");
    }

    @Test
    void recusaUsuarioSemSenhaDefinidaSemRevelarQueEleNaoTemSenha() {
        Usuario semSenha = usuarioComSenha();
        semSenha.setSenhaHash(null);
        when(usuarioRepository.findByEmail("larissa@conectsol.com"))
                .thenReturn(Optional.of(semSenha));

        assertThatThrownBy(() -> servico.autenticar("larissa@conectsol.com", SENHA_CORRETA))
                .isInstanceOf(CredenciaisInvalidasException.class)
                .hasMessage("Credenciais inválidas");
    }

    @Test
    void recusaUsuarioInativo() {
        Usuario inativo = usuarioComSenha();
        inativo.setAtivo(false);
        when(usuarioRepository.findByEmail("larissa@conectsol.com"))
                .thenReturn(Optional.of(inativo));

        assertThatThrownBy(() -> servico.autenticar("larissa@conectsol.com", SENHA_CORRETA))
                .isInstanceOf(CredenciaisInvalidasException.class)
                .hasMessage("Credenciais inválidas");
    }
}
