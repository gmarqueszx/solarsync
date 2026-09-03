package com.conectsol.solarsync.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Define a senha do administrador semeado a partir de uma variável de ambiente, uma única vez.
 * <p>
 * Existe por dois motivos concretos: o admin da migration V3 nasce com {@code senha_hash} nulo
 * (o acesso previsto é pelo Google Workspace), então <b>sem isto não há como entrar no sistema
 * enquanto o login Google não estiver configurado</b> — nem em desenvolvimento, nem no primeiro
 * deploy. E serve de conta break-glass se o Google ficar indisponível.
 * <p>
 * Só age quando a senha ainda é nula: nunca sobrescreve uma senha já definida, então deixar a
 * variável no ambiente não vira um reset silencioso a cada restart. A senha vem do ambiente,
 * nunca do repositório (checklist item 6 do CLAUDE.md).
 */
@Component
@ConditionalOnProperty("solarsync.admin.senha-inicial")
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String senhaInicial;

    public AdminBootstrap(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder,
            @Value("${solarsync.admin.email:joaogabriel@conectsol.com}") String email,
            @Value("${solarsync.admin.senha-inicial}") String senhaInicial) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.senhaInicial = senhaInicial;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments argumentos) {
        usuarioRepository.findByEmail(Usuario.normalizarEmail(email)).ifPresentOrElse(
                this::definirSenhaSeAusente,
                () -> log.warn("solarsync.admin.senha-inicial definida, mas não há usuário {}",
                        email));
    }

    private void definirSenhaSeAusente(Usuario admin) {
        if (admin.getSenhaHash() != null) {
            log.debug("Admin {} já tem senha definida; nada a fazer.", admin.getEmail());
            return;
        }
        admin.setSenhaHash(passwordEncoder.encode(senhaInicial));
        usuarioRepository.save(admin);
        log.warn("Senha inicial aplicada ao admin {}. Troque-a em POST /api/usuarios/{}/senha "
                + "e remova SOLARSYNC_ADMIN_SENHA_INICIAL do ambiente.",
                admin.getEmail(), admin.getId());
    }
}
