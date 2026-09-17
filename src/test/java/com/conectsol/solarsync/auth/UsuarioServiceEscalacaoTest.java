package com.conectsol.solarsync.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.conectsol.solarsync.auth.dto.UsuarioCriarRequest;
import com.conectsol.solarsync.common.security.UsuarioAutenticado;

/**
 * A guarda que impede o GESTOR de virar ADMINISTRADOR por conta própria.
 * <p>
 * O `@PreAuthorize` do controller só sabe dizer <i>quem entra</i> no endpoint; quem decide <i>o
 * que pode ser feito lá dentro</i> é o service — e é por isso que este teste existe aqui, e não
 * junto com a matriz do {@link UsuarioControllerRbacTest}.
 */
@ExtendWith(MockitoExtension.class)
class UsuarioServiceEscalacaoTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private PapelRepository papelRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UsuarioService servico;

    private UsuarioAutenticado gestor;
    private UsuarioAutenticado administrador;

    @BeforeEach
    void identidades() {
        gestor = new UsuarioAutenticado(1L, "igor@conectsol.com", "Igor",
                Set.of(NomePapel.GESTOR));
        administrador = new UsuarioAutenticado(2L, "joao@conectsol.com", "João",
                Set.of(NomePapel.ADMINISTRADOR));
    }

    private static UsuarioCriarRequest cadastro(NomePapel papel) {
        return new UsuarioCriarRequest("Nova Pessoa", "nova@conectsol.com", Set.of(papel),
                "senha-provisoria-1");
    }

    @Test
    void gestorNaoCriaAdministrador() {
        assertThatThrownBy(() -> servico.criar(cadastro(NomePapel.ADMINISTRADOR), gestor))
                .isInstanceOf(AccessDeniedException.class);

        // Falhar depois de gravar seria pior do que não ter a guarda.
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void administradorCriaAdministrador() {
        Papel papel = new Papel();
        papel.setNome(NomePapel.ADMINISTRADOR);
        when(papelRepository.findByNome(NomePapel.ADMINISTRADOR)).thenReturn(Optional.of(papel));
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(usuarioRepository.save(any())).thenAnswer(invocacao -> {
            Usuario salvo = invocacao.getArgument(0);
            salvo.setId(9L);
            return salvo;
        });

        assertThatCode(() -> servico.criar(cadastro(NomePapel.ADMINISTRADOR), administrador))
                .doesNotThrowAnyException();
    }

    @Test
    void gestorNaoRedefineSenhaDeAdministrador() {
        Papel papelAdmin = new Papel();
        papelAdmin.setNome(NomePapel.ADMINISTRADOR);
        Usuario alvo = Usuario.builder()
                .nome("João").email("joao@conectsol.com").ativo(true)
                .papeis(Set.of(papelAdmin))
                .build();
        alvo.setId(2L);
        when(usuarioRepository.findById(2L)).thenReturn(Optional.of(alvo));

        assertThatThrownBy(() -> servico.definirSenha(2L, "outra-senha-123", gestor))
                .isInstanceOf(AccessDeniedException.class);

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void ninguemDesativaAPropriaConta() {
        Papel papelGestor = new Papel();
        papelGestor.setNome(NomePapel.GESTOR);
        Usuario eu = Usuario.builder()
                .nome("Igor").email("igor@conectsol.com").ativo(true)
                .papeis(Set.of(papelGestor))
                .build();
        eu.setId(1L);
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(eu));

        assertThatThrownBy(() -> servico.alterarAtivacao(1L, false, gestor))
                .isInstanceOf(AccessDeniedException.class);

        verify(usuarioRepository, never()).save(any());
    }
}
