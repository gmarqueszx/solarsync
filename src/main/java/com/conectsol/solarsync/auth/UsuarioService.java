package com.conectsol.solarsync.auth;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.auth.dto.UsuarioAtualizarRequest;
import com.conectsol.solarsync.auth.dto.UsuarioCriarRequest;
import com.conectsol.solarsync.auth.dto.UsuarioResponse;
import com.conectsol.solarsync.auth.dto.UsuarioResumoResponse;
import com.conectsol.solarsync.common.security.UsuarioAutenticado;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Gestão de usuários, feita por ADMINISTRADOR e GESTOR. É o <b>único</b> caminho de entrada de
 * gente no sistema: não há auto-cadastro nem login federado desde 16/09/2026, então quem não
 * for cadastrado aqui não entra.
 */
@Service
@RequiredArgsConstructor
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final PapelRepository papelRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<UsuarioResponse> listar() {
        return usuarioRepository.findAll().stream().map(UsuarioResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public List<UsuarioResumoResponse> lookup(boolean somenteAtivos) {
        return usuarioRepository.findAll().stream()
                .filter(usuario -> !somenteAtivos || usuario.isAtivo())
                .map(UsuarioResumoResponse::de)
                .toList();
    }

    @Transactional(readOnly = true)
    public UsuarioResponse buscar(Long id) {
        return UsuarioResponse.de(carregar(id));
    }

    @Transactional
    public UsuarioResponse criar(UsuarioCriarRequest requisicao, UsuarioAutenticado autor) {
        exigirPoderSobreOsPapeis(requisicao.papeis(), autor);

        Usuario usuario = Usuario.builder()
                .nome(requisicao.nome())
                .email(Usuario.normalizarEmail(requisicao.email()))
                .ativo(true)
                .papeis(resolverPapeis(requisicao.papeis()))
                .build();
        usuario.setSenhaHash(passwordEncoder.encode(requisicao.senha()));

        return UsuarioResponse.de(usuarioRepository.save(usuario));
    }

    @Transactional
    public UsuarioResponse atualizar(Long id, UsuarioAtualizarRequest requisicao,
            UsuarioAutenticado autor) {

        Usuario usuario = carregar(id);
        exigirPoderSobre(usuario, autor);
        exigirPoderSobreOsPapeis(requisicao.papeis(), autor);

        usuario.setNome(requisicao.nome());
        usuario.setEmail(Usuario.normalizarEmail(requisicao.email()));
        usuario.setPapeis(resolverPapeis(requisicao.papeis()));
        return UsuarioResponse.de(usuarioRepository.save(usuario));
    }

    @Transactional
    public UsuarioResponse definirSenha(Long id, String senha, UsuarioAutenticado autor) {
        Usuario usuario = carregar(id);
        exigirPoderSobre(usuario, autor);
        usuario.setSenhaHash(passwordEncoder.encode(senha));
        return UsuarioResponse.de(usuarioRepository.save(usuario));
    }

    @Transactional
    public UsuarioResponse alterarAtivacao(Long id, boolean ativo, UsuarioAutenticado autor) {
        Usuario usuario = carregar(id);
        exigirPoderSobre(usuario, autor);
        if (!ativo && usuario.getId().equals(autor.id())) {
            throw new AccessDeniedException("Ninguém desativa a própria conta");
        }
        usuario.setAtivo(ativo);
        return UsuarioResponse.de(usuarioRepository.save(usuario));
    }

    /**
     * Exclusão de verdade. Falha com 409 se o usuário já aparece em {@code historico_status}
     * (FK) — nesse caso o caminho correto é desativar, preservando a auditoria.
     */
    @Transactional
    public void excluir(Long id) {
        usuarioRepository.delete(carregar(id));
    }

    /**
     * O GESTOR administra a equipe, não os administradores. Sem esta guarda ele poderia criar
     * uma conta ADMINISTRADOR, entrar por ela e apagar registros — e a linha "Apagar: só ADMIN"
     * da matriz RBAC viraria decoração.
     */
    private static void exigirPoderSobreOsPapeis(Set<NomePapel> papeis, UsuarioAutenticado autor) {
        if (papeis.contains(NomePapel.ADMINISTRADOR) && !autor.tem(NomePapel.ADMINISTRADOR)) {
            throw new AccessDeniedException("Só um ADMINISTRADOR concede o papel ADMINISTRADOR");
        }
    }

    /** Pelo mesmo motivo: gestor não redefine senha nem desativa a conta de um administrador. */
    private static void exigirPoderSobre(Usuario alvo, UsuarioAutenticado autor) {
        boolean alvoEhAdmin = alvo.getPapeis().stream()
                .anyMatch(papel -> papel.getNome() == NomePapel.ADMINISTRADOR);
        if (alvoEhAdmin && !autor.tem(NomePapel.ADMINISTRADOR)) {
            throw new AccessDeniedException("Só um ADMINISTRADOR altera outro ADMINISTRADOR");
        }
    }

    private Set<Papel> resolverPapeis(Set<NomePapel> nomes) {
        return nomes.stream()
                .map(nome -> papelRepository.findByNome(nome).orElseThrow(
                        () -> new EntityNotFoundException("Papel não encontrado: " + nome)))
                .collect(Collectors.toSet());
    }

    private Usuario carregar(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Usuário não encontrado: " + id));
    }
}
