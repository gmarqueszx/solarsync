package com.conectsol.solarsync.auth;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.auth.dto.UsuarioRequest;
import com.conectsol.solarsync.auth.dto.UsuarioResponse;
import com.conectsol.solarsync.auth.dto.UsuarioResumoResponse;
import com.conectsol.solarsync.auth.login.LoginSenhaService;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Gestão de usuários, restrita a ADMINISTRADOR. Existe porque o login com Google não faz
 * auto-cadastro: alguém precisa cadastrar as pessoas antes do primeiro acesso, e só o admin
 * semeado pela V3 começa no banco.
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
    public UsuarioResponse criar(UsuarioRequest requisicao) {
        Usuario usuario = Usuario.builder()
                .nome(requisicao.nome())
                .email(Usuario.normalizarEmail(requisicao.email()))
                .ativo(true)
                .papeis(resolverPapeis(requisicao.papeis()))
                .build();

        if (requisicao.senha() != null && !requisicao.senha().isBlank()) {
            usuario.setSenhaHash(passwordEncoder.encode(requisicao.senha()));
        }

        return UsuarioResponse.de(usuarioRepository.save(usuario));
    }

    @Transactional
    public UsuarioResponse atualizar(Long id, UsuarioRequest requisicao) {
        Usuario usuario = carregar(id);
        usuario.setNome(requisicao.nome());
        usuario.setEmail(Usuario.normalizarEmail(requisicao.email()));
        usuario.setPapeis(resolverPapeis(requisicao.papeis()));
        return UsuarioResponse.de(usuarioRepository.save(usuario));
    }

    @Transactional
    public UsuarioResponse definirSenha(Long id, String senha) {
        Usuario usuario = carregar(id);
        usuario.setSenhaHash(passwordEncoder.encode(senha));
        return UsuarioResponse.de(usuarioRepository.save(usuario));
    }

    @Transactional
    public UsuarioResponse alterarAtivacao(Long id, boolean ativo) {
        Usuario usuario = carregar(id);
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
