package com.conectsol.solarsync.auth;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.auth.dto.UsuarioAtualizarRequest;
import com.conectsol.solarsync.auth.dto.UsuarioCriarRequest;
import com.conectsol.solarsync.auth.dto.UsuarioResponse;
import com.conectsol.solarsync.auth.dto.UsuarioResumoResponse;
import com.conectsol.solarsync.auth.event.UsuarioAlteradoEvent;
import com.conectsol.solarsync.common.EntidadeTipo;
import com.conectsol.solarsync.common.security.UsuarioAutenticado;
import com.conectsol.solarsync.historico.HistoricoStatusService;
import com.conectsol.solarsync.historico.dto.HistoricoStatusResponse;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Gestão de usuários, feita por ADMINISTRADOR e GESTOR. É o <b>único</b> caminho de entrada de
 * gente no sistema: não há auto-cadastro nem login federado desde 16/09/2026, então quem não
 * for cadastrado aqui não entra.
 * <p>
 * Toda alteração publica um {@link UsuarioAlteradoEvent}, que o listener de auditoria grava em
 * {@code historico_status} na mesma transação — como nos outros módulos.
 */
@Service
@RequiredArgsConstructor
public class UsuarioService {

    static final String SENHA_DEFINIDA = "SENHA_DEFINIDA";
    static final String EMAIL_ALTERADO = "EMAIL_ALTERADO";

    private final UsuarioRepository usuarioRepository;
    private final PapelRepository papelRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventos;
    private final HistoricoStatusService historicoStatusService;

    /** Sem as contas de sistema: não são gente, e a tela não tem o que fazer com elas. */
    @Transactional(readOnly = true)
    public List<UsuarioResponse> listar() {
        return usuarioRepository.findAll().stream()
                .filter(usuario -> !usuario.isContaSistema())
                .map(UsuarioResponse::de)
                .toList();
    }

    /**
     * Mantém a conta de integração quando {@code somenteAtivos=false}: é por aqui que a tela
     * resolve o nome do autor de uma linha do histórico, e ela é a autora das mudanças
     * automáticas. Só id e nome saem daqui.
     */
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

    /** Quem ativou, desativou, redefiniu a senha ou mudou os papéis deste usuário, e quando. */
    @Transactional(readOnly = true)
    public List<HistoricoStatusResponse> historico(Long id) {
        carregar(id);
        return historicoStatusService.listar(EntidadeTipo.USUARIO, id);
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

        Usuario salvo = usuarioRepository.save(usuario);
        registrar(salvo, null, descreverPapeis(salvo.getPapeis()), autor);
        return UsuarioResponse.de(salvo);
    }

    @Transactional
    public UsuarioResponse atualizar(Long id, UsuarioAtualizarRequest requisicao,
            UsuarioAutenticado autor) {

        Usuario usuario = carregar(id);
        exigirContaDePessoa(usuario);
        exigirPoderSobre(usuario, autor);
        exigirPoderSobreOsPapeis(requisicao.papeis(), autor);

        String papeisAntes = descreverPapeis(usuario.getPapeis());
        String emailAntes = usuario.getEmail();

        usuario.setNome(requisicao.nome());
        usuario.setEmail(Usuario.normalizarEmail(requisicao.email()));
        usuario.setPapeis(resolverPapeis(requisicao.papeis()));
        Usuario salvo = usuarioRepository.save(usuario);

        String papeisDepois = descreverPapeis(salvo.getPapeis());
        if (!papeisDepois.equals(papeisAntes)) {
            registrar(salvo, papeisAntes, papeisDepois, autor);
        }
        // O e-mail é o login: trocá-lo e depois redefinir a senha é outra forma de assumir a
        // conta, então a troca também deixa rastro.
        if (!salvo.getEmail().equals(emailAntes)) {
            registrar(salvo, null, EMAIL_ALTERADO, autor);
        }
        return UsuarioResponse.de(salvo);
    }

    @Transactional
    public UsuarioResponse definirSenha(Long id, String senha, UsuarioAutenticado autor) {
        Usuario usuario = carregar(id);
        exigirContaDePessoa(usuario);
        exigirPoderSobre(usuario, autor);
        usuario.setSenhaHash(passwordEncoder.encode(senha));
        Usuario salvo = usuarioRepository.save(usuario);
        registrar(salvo, null, SENHA_DEFINIDA, autor);
        return UsuarioResponse.de(salvo);
    }

    @Transactional
    public UsuarioResponse alterarAtivacao(Long id, boolean ativo, UsuarioAutenticado autor) {
        Usuario usuario = carregar(id);
        exigirContaDePessoa(usuario);
        exigirPoderSobre(usuario, autor);
        if (!ativo && usuario.getId().equals(autor.id())) {
            throw new AccessDeniedException("Ninguém desativa a própria conta");
        }
        if (usuario.isAtivo() == ativo) {
            // Duplo clique não vira duas linhas no histórico, como nas transições dos módulos.
            return UsuarioResponse.de(usuario);
        }
        usuario.setAtivo(ativo);
        Usuario salvo = usuarioRepository.save(usuario);
        registrar(salvo, ativo ? "INATIVO" : "ATIVO", ativo ? "ATIVO" : "INATIVO", autor);
        return UsuarioResponse.de(salvo);
    }

    /**
     * Exclusão de verdade. Falha com 409 se o usuário já aparece em {@code historico_status}
     * (FK) — nesse caso o caminho correto é desativar, preservando a auditoria.
     */
    @Transactional
    public void excluir(Long id) {
        Usuario usuario = carregar(id);
        exigirContaDePessoa(usuario);
        usuarioRepository.delete(usuario);
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

    /**
     * A conta de integração não tem papel, então {@link #exigirPoderSobre} a deixava passar: um
     * GESTOR a reativava, dava senha e papel, e entrava por ela — e tudo o que fizesse ficava no
     * histórico como "Integração automática". Vale para qualquer papel do autor, administrador
     * inclusive: não há operação legítima de tela sobre essa conta.
     */
    private static void exigirContaDePessoa(Usuario alvo) {
        if (alvo.isContaSistema()) {
            throw new AccessDeniedException(
                    "Conta de sistema não é gerenciável: ela é a autora das mudanças automáticas");
        }
    }

    private void registrar(Usuario alvo, String anterior, String novo, UsuarioAutenticado autor) {
        eventos.publishEvent(
                new UsuarioAlteradoEvent(alvo.getId(), anterior, novo, Instant.now(), autor.id()));
    }

    private static String descreverPapeis(Set<Papel> papeis) {
        return papeis.stream()
                .map(papel -> papel.getNome().name())
                .sorted()
                .collect(Collectors.joining(","));
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
