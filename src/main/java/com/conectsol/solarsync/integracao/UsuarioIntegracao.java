package com.conectsol.solarsync.integracao;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;

import lombok.RequiredArgsConstructor;

/**
 * O autor das mudanças de status feitas pelas integrações, para a linha do tempo do projeto
 * dizer "Integração automática" em vez de deixar o autor em branco — indistinguível de um
 * registro importado da planilha.
 * <p>
 * A conta é semeada pela migration V13, inativa e sem senha: não entra pelo login, não renova
 * token e não aparece no lookup de analistas (que filtra por {@code ativo}), então não há como
 * alguém atribuir trabalho a ela por engano. Os jobs chamam os services direto, sem passar por
 * {@code @PreAuthorize}, então ela também não precisa de papel nenhum.
 */
@Component
@RequiredArgsConstructor
public class UsuarioIntegracao {

    /** Mesmo e-mail semeado pela migration V13. Mudar aqui exige mudar lá. */
    public static final String EMAIL = "integracao@conectsol.com";

    private static final Logger log = LoggerFactory.getLogger(UsuarioIntegracao.class);

    private final UsuarioRepository usuarioRepository;

    /**
     * Resolvido na primeira chamada e memorizado: é o mesmo id em toda execução do job, e
     * consultar por e-mail a cada e-mail lido seria consulta repetida sem motivo.
     */
    private final AtomicReference<Long> idMemorizado = new AtomicReference<>();

    /**
     * Nulo se a conta não existir (alguém apagou a linha da V13). Nulo é aceitável de propósito:
     * {@code historico_status.usuario_id} é nulável, então o pior caso é a linha do tempo ficar
     * sem autor — não vale abortar a leitura do e-mail da Coelba por causa disso.
     */
    public Long id() {
        Long memorizado = idMemorizado.get();
        if (memorizado != null) {
            return memorizado;
        }

        Long resolvido = usuarioRepository.findByEmail(Usuario.normalizarEmail(EMAIL))
                .map(Usuario::getId)
                .orElse(null);

        if (resolvido == null) {
            log.warn("Conta de integração {} não encontrada: as mudanças automáticas de status "
                    + "vão para o histórico sem autor. Ela é semeada pela migration V13.", EMAIL);
            return null;
        }

        idMemorizado.set(resolvido);
        return resolvido;
    }
}
