package com.conectsol.solarsync.auth.login;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.common.exception.LimiteDeTentativasException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * Conta as falhas de login por e-mail e por origem, e recusa quando passam do limite — item 11
 * do checklist da seção 8. É o que impede o BCrypt do login de virar vetor de negação de serviço.
 *
 * <h2>Em memória, e por que basta</h2>
 * O SolarSync roda numa instância só, num VPS. Um contador em memória não sobrevive a restart e
 * não é compartilhado entre instâncias — as duas limitações são reais e estão registradas na
 * seção 11 do CLAUDE.md. A alternativa (Redis, ou uma tabela) acrescentaria infraestrutura para
 * um sistema de uma empresa só, e o restart zerar o contador só devolve ao atacante a janela que
 * ele já teria em quinze minutos.
 *
 * <h2>Janela fixa, não deslizante</h2>
 * Contador simples que zera a cada {@code janela}. A janela deslizante seria mais justa na
 * fronteira — quem gasta o limite no fim de uma janela recomeça cheio na seguinte —, mas exigiria
 * guardar o instante de cada tentativa. Para o que isto protege, a diferença é o dobro do limite
 * num instante específico, e o limite já é folgado.
 */
@Component
@RequiredArgsConstructor
public class ControleDeTentativasDeLogin {

    private static final Logger log = LoggerFactory.getLogger(ControleDeTentativasDeLogin.class);

    /**
     * A partir daqui, uma gravação limpa o que expirou. Não é limite de uso: é a proteção contra
     * o mapa crescer sem fim quando o atacante varia o e-mail a cada tentativa — que é
     * exatamente o que faz um ataque de enumeração.
     */
    private static final int TAMANHO_PARA_LIMPAR = 10_000;

    private final Map<String, Janela> contadores = new ConcurrentHashMap<>();
    private final LoginRateLimitProperties propriedades;

    /** Quantas falhas houve nesta chave, e quando a contagem começou. */
    private record Janela(Instant inicio, int falhas) {

        boolean expirou(Instant agora, Duration duracao) {
            return inicio.plus(duracao).isBefore(agora);
        }
    }

    /**
     * Recusa antes de qualquer trabalho caro. Chamado <b>antes</b> de ler o usuário e de comparar
     * a senha — é essa ordem que faz o limite proteger contra negação de serviço: passar pelo
     * BCrypt para só depois recusar gastaria exatamente o recurso que se quer poupar.
     */
    public void verificar(String origem, String email) {
        Instant agora = Instant.now();
        conferir(chaveDeEmail(email), propriedades.tentativasPorEmail(), agora);
        conferir(chaveDeOrigem(origem), propriedades.tentativasPorIp(), agora);
    }

    /** Uma tentativa que falhou. Conta nas duas chaves. */
    public void falhou(String origem, String email) {
        Instant agora = Instant.now();
        somar(chaveDeEmail(email), agora);
        somar(chaveDeOrigem(origem), agora);

        if (contadores.size() > TAMANHO_PARA_LIMPAR) {
            contadores.values().removeIf(janela -> janela.expirou(agora, propriedades.janela()));
        }
    }

    /**
     * Login certo zera o contador do e-mail — e <b>não</b> o da origem. Quem acertou a senha
     * provou ser dono da conta, então as tentativas erradas anteriores foram dedo trocado. Já a
     * origem pode ser um atacante que acertou uma conta entre muitas tentativas, e zerar ali lhe
     * daria a janela inteira de volta.
     */
    public void acertou(String email) {
        contadores.remove(chaveDeEmail(email));
    }

    /**
     * De onde veio a requisição.
     * <p>
     * ⚠️ {@code X-Forwarded-For} só é lido com {@code solarsync.login.confiar-em-proxy=true}, e
     * só o <b>primeiro</b> endereço da lista. O cabeçalho é escrito pelo cliente quando não há
     * proxy na frente: confiar nele sem proxy deixaria qualquer um trocar de origem a cada
     * requisição, e o limite por origem deixaria de existir.
     */
    public String origemDe(HttpServletRequest requisicao) {
        if (propriedades.confiarEmProxy()) {
            String encaminhado = requisicao.getHeader("X-Forwarded-For");
            if (encaminhado != null && !encaminhado.isBlank()) {
                return encaminhado.split(",")[0].trim();
            }
        }
        String remoto = requisicao.getRemoteAddr();
        return remoto == null ? "desconhecida" : remoto;
    }

    private void conferir(String chave, int limite, Instant agora) {
        Janela janela = contadores.get(chave);
        if (janela == null || janela.expirou(agora, propriedades.janela())) {
            return;
        }
        if (janela.falhas() >= limite) {
            Duration esperar = Duration.between(agora, janela.inicio().plus(propriedades.janela()));
            log.warn("Login recusado por limite de tentativas ({} falhas em {})",
                    janela.falhas(), chave);
            throw new LimiteDeTentativasException(esperar.isNegative() ? Duration.ZERO : esperar);
        }
    }

    /** {@code compute} e não get+put: duas tentativas simultâneas contariam como uma. */
    private void somar(String chave, Instant agora) {
        contadores.compute(chave, (ignorada, atual) ->
                atual == null || atual.expirou(agora, propriedades.janela())
                        ? new Janela(agora, 1)
                        : new Janela(atual.inicio(), atual.falhas() + 1));
    }

    /**
     * Normalizado como no login, senão "JOAO@x.com" e "joao@x.com" teriam contadores separados e
     * o limite por conta seria contornável só variando a caixa.
     */
    private static String chaveDeEmail(String email) {
        return "email:" + Usuario.normalizarEmail(email);
    }

    private static String chaveDeOrigem(String origem) {
        return "origem:" + origem;
    }
}
