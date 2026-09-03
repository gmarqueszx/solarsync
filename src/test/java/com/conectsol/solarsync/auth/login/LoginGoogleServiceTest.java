package com.conectsol.solarsync.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.conectsol.solarsync.auth.NomePapel;
import com.conectsol.solarsync.auth.Papel;
import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.auth.google.GoogleProperties;
import com.conectsol.solarsync.auth.google.VerificadorIdTokenGoogle;
import com.conectsol.solarsync.auth.google.VerificadorIdTokenGoogle.IdentidadeGoogle;
import com.conectsol.solarsync.auth.jwt.TokenService;
import com.conectsol.solarsync.auth.jwt.TokenService.ParDeTokens;
import com.conectsol.solarsync.common.exception.UsuarioNaoAutorizadoException;

/**
 * O requisito de segurança mais importante desta fase: <b>login Google não cadastra
 * ninguém</b>. Cada cenário abaixo termina afirmando que nenhum {@code save} aconteceu.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginGoogleServiceTest {

    @Mock
    private VerificadorIdTokenGoogle verificador;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private TokenService tokenService;

    private LoginGoogleService servico;

    @BeforeEach
    void montarServico() {
        GoogleProperties propriedades =
                new GoogleProperties("client-id-de-teste", List.of("conectsol.com"), null, null);
        servico = new LoginGoogleService(
                verificador, propriedades, usuarioRepository, tokenService);
    }

    private static Usuario usuarioAtivo() {
        Usuario usuario = Usuario.builder()
                .nome("Ivan Analista")
                .email("ivan@conectsol.com")
                .ativo(true)
                .papeis(Set.of(Papel.builder().nome(NomePapel.ANALISTA).build()))
                .build();
        usuario.setId(7L);
        return usuario;
    }

    private void verificadorDevolve(String email, boolean verificado, String hostedDomain) {
        when(verificador.verificar("token"))
                .thenReturn(new IdentidadeGoogle(email, verificado, hostedDomain, "Nome"));
    }

    @Test
    void emiteTokenParaUsuarioCadastradoEAtivo() {
        verificadorDevolve("ivan@conectsol.com", true, "conectsol.com");
        Usuario usuario = usuarioAtivo();
        when(usuarioRepository.findByEmail("ivan@conectsol.com")).thenReturn(Optional.of(usuario));
        when(tokenService.emitirPar(usuario))
                .thenReturn(new ParDeTokens("access", "refresh", 900));

        var resposta = servico.autenticar("token");

        assertThat(resposta.accessToken()).isEqualTo("access");
        assertThat(resposta.usuario().email()).isEqualTo("ivan@conectsol.com");
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void recusaEmailSemUsuarioCadastrado() {
        verificadorDevolve("estranho@conectsol.com", true, "conectsol.com");
        when(usuarioRepository.findByEmail("estranho@conectsol.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.autenticar("token"))
                .isInstanceOf(UsuarioNaoAutorizadoException.class);

        // A garantia central: nenhum caminho deste serviço cria usuário.
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void recusaUsuarioCadastradoMasInativo() {
        verificadorDevolve("ivan@conectsol.com", true, "conectsol.com");
        Usuario inativo = usuarioAtivo();
        inativo.setAtivo(false);
        when(usuarioRepository.findByEmail("ivan@conectsol.com")).thenReturn(Optional.of(inativo));

        assertThatThrownBy(() -> servico.autenticar("token"))
                .isInstanceOf(UsuarioNaoAutorizadoException.class);
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void recusaDominioForaDaAllowlist() {
        verificadorDevolve("qualquer@gmail.com", true, null);

        assertThatThrownBy(() -> servico.autenticar("token"))
                .isInstanceOf(UsuarioNaoAutorizadoException.class);
        verify(usuarioRepository, never()).findByEmail(any());
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void recusaHostedDomainDivergenteDoDominioDoEmail() {
        // Conta de Workspace de outra organização usando um e-mail com o nosso domínio.
        verificadorDevolve("ivan@conectsol.com", true, "outraempresa.com");

        assertThatThrownBy(() -> servico.autenticar("token"))
                .isInstanceOf(UsuarioNaoAutorizadoException.class);
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void recusaEmailNaoVerificadoPeloGoogle() {
        verificadorDevolve("ivan@conectsol.com", false, "conectsol.com");

        assertThatThrownBy(() -> servico.autenticar("token"))
                .isInstanceOf(UsuarioNaoAutorizadoException.class);
        verify(usuarioRepository, never()).findByEmail(any());
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void normalizaEmailAntesDeBuscar() {
        // O Google devolve minúsculas; um cadastro feito com maiúscula precisa casar.
        verificadorDevolve("  Ivan@ConectSol.com ", true, "conectsol.com");
        Usuario usuario = usuarioAtivo();
        when(usuarioRepository.findByEmail("ivan@conectsol.com")).thenReturn(Optional.of(usuario));
        when(tokenService.emitirPar(usuario))
                .thenReturn(new ParDeTokens("access", "refresh", 900));

        assertThat(servico.autenticar("token").accessToken()).isEqualTo("access");
        verify(usuarioRepository).findByEmail("ivan@conectsol.com");
    }
}
