package com.conectsol.solarsync.auth.login;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limite de tentativas no {@code POST /api/auth/login} — item 11 do checklist da seção 8.
 * <p>
 * O endpoint é público e chama BCrypt, que é caro <b>de propósito</b> (é o que torna um vazamento
 * de hashes difícil de explorar). Sem limite, essa mesma lentidão vira o ataque: algumas centenas
 * de requisições por segundo ocupam todas as threads do servidor com hash de senha e o sistema
 * inteiro para, sem que ninguém tenha adivinhado senha nenhuma. Em segundo lugar, o limite
 * também encarece a tentativa de adivinhar a senha de uma conta específica.
 *
 * @param tentativasPorEmail falhas consecutivas por e-mail antes de recusar. Conta por e-mail, e
 *                           não só por IP, porque quem tenta adivinhar a senha de uma pessoa pode
 *                           trocar de origem; e é o contador que o login bem-sucedido zera
 * @param tentativasPorIp    falhas por origem. <b>Folgado de propósito</b>: a ConectSol inteira
 *                           sai por um IP só, então um limite apertado aqui trancaria o escritório
 *                           todo porque uma pessoa errou a senha. Quem protege a conta é o limite
 *                           por e-mail; este existe para conter a enxurrada
 * @param janela             de quanto em quanto tempo os contadores zeram
 * @param confiarEmProxy     ⚠️ lê o IP do cliente de {@code X-Forwarded-For} em vez da conexão.
 *                           <b>Só ligue com um proxy de verdade na frente</b> (o Cloudflare do
 *                           item 11 do checklist): sem ele, qualquer um forja o cabeçalho, muda
 *                           de "IP" a cada requisição e o limite por origem deixa de existir.
 *                           Desligado, o IP é o da conexão — que atrás de um proxy é sempre o do
 *                           proxy, e aí o limite por origem viraria um limite global
 */
@ConfigurationProperties("solarsync.login")
public record LoginRateLimitProperties(
        int tentativasPorEmail,
        int tentativasPorIp,
        Duration janela,
        boolean confiarEmProxy) {

    public LoginRateLimitProperties {
        tentativasPorEmail = tentativasPorEmail <= 0 ? 10 : tentativasPorEmail;
        tentativasPorIp = tentativasPorIp <= 0 ? 60 : tentativasPorIp;
        janela = janela == null ? Duration.ofMinutes(15) : janela;
    }
}
