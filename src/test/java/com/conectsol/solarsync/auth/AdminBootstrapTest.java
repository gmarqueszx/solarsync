package com.conectsol.solarsync.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Achado A-02 da auditoria: a senha inicial do administrador era aplicada sem regra nenhuma, e
 * "123" abria a conta que apaga registros, exposta ao login público.
 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    private static final String EMAIL = "joaogabriel@conectsol.com";

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private Usuario adminSemSenha() {
        Usuario admin = Usuario.builder().nome("João").email(EMAIL).ativo(true).build();
        admin.setId(1L);
        return admin;
    }

    @Test
    void senhaInicialCurtaDerrubaOBootSemGravarNada() {
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.of(adminSemSenha()));
        AdminBootstrap bootstrap =
                new AdminBootstrap(usuarioRepository, passwordEncoder, EMAIL, "123");

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12 caracteres");
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void senhaInicialForteEAplicada() {
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.of(adminSemSenha()));
        when(passwordEncoder.encode("uma-senha-bem-longa")).thenReturn("hash");
        AdminBootstrap bootstrap = new AdminBootstrap(usuarioRepository, passwordEncoder, EMAIL,
                "uma-senha-bem-longa");

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();
        verify(usuarioRepository).save(any());
    }

    /** Num banco que já tem senha o valor é ignorado; barrar o boot por ele seria ruído. */
    @Test
    void senhaCurtaIgnoradaNaoDerrubaOBootQuandoOAdminJaTemSenha() {
        Usuario admin = adminSemSenha();
        admin.setSenhaHash("hash-existente");
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.of(admin));
        AdminBootstrap bootstrap =
                new AdminBootstrap(usuarioRepository, passwordEncoder, EMAIL, "123");

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();
        verify(usuarioRepository, never()).save(any());
    }
}
