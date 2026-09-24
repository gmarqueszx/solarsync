package com.conectsol.solarsync.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.conectsol.solarsync.TestcontainersConfiguration;
import com.conectsol.solarsync.auth.NomePapel;
import com.conectsol.solarsync.auth.PapelRepository;
import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;

/**
 * Item 11 do checklist da seção 8: o {@code POST /api/auth/login} é público e chama BCrypt, que
 * é caro de propósito. Sem limite de tentativas, essa lentidão vira o ataque — algumas centenas
 * de requisições por segundo ocupam as threads do servidor com hash de senha e derrubam o
 * sistema inteiro, sem ninguém ter adivinhado senha nenhuma.
 * <p>
 * Os limites são apertados por propriedade aqui (3 por e-mail, 4 por origem) para o teste ser
 * legível; os padrões de produção são bem mais folgados e estão em
 * {@link LoginRateLimitProperties}.
 * <p>
 * Não estende {@code AbstractIntegrationTest} porque precisa dos limites baixos, e um
 * {@code @SpringBootTest} na subclasse substitui o da superclasse em vez de somar — daí as
 * propriedades de lá estarem repetidas.
 */
@SpringBootTest(properties = {
        "solarsync.dados-de-exemplo=false",
        "solarsync.nectar.ativo=false",
        "solarsync.gmail.ativo=false",
        "solarsync.gmail.somente-conferencia=false",
        "solarsync.login.tentativas-por-email=3",
        "solarsync.login.tentativas-por-ip=4",
        "solarsync.login.janela=PT15M" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class LimiteDeTentativasLoginHttpTest {

    private static final String EMAIL = "limite.tentativas@conectsol.com";
    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PapelRepository papelRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ControleDeTentativasDeLogin controle;

    @BeforeEach
    void preparar() {
        usuarioRepository.findByEmail(EMAIL).ifPresent(usuarioRepository::delete);
        usuarioRepository.save(Usuario.builder()
                .nome("Analista do Limite")
                .email(EMAIL)
                .senhaHash(passwordEncoder.encode(SENHA))
                .ativo(true)
                .papeis(Set.of(papelRepository.findByNome(NomePapel.ANALISTA).orElseThrow()))
                .build());
    }

    /**
     * O contador vive em memória e é compartilhado pelo contexto do Spring, que é reaproveitado
     * entre testes. Sem zerar, um teste envenena o seguinte — e a falha apareceria como "429
     * inesperado" num teste que nem é sobre limite.
     */
    @AfterEach
    void limpar() {
        controle.acertou(EMAIL);
        usuarioRepository.findByEmail(EMAIL).ifPresent(usuarioRepository::delete);
    }

    @Test
    void recusaComQuatrocentosEVinteENoveDepoisDoLimitePorEmail() throws Exception {
        for (int i = 0; i < 3; i++) {
            tentar(EMAIL, "senha-errada", "10.0.0." + i)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.codigo").value("CREDENCIAIS_INVALIDAS"));
        }

        // A quarta, de outra origem ainda: o contador por e-mail é o que protege a conta de quem
        // troca de IP a cada tentativa.
        tentar(EMAIL, "senha-errada", "10.0.0.99")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("LIMITE_DE_TENTATIVAS"))
                .andExpect(header().exists("Retry-After"));
    }

    /**
     * ⚠️ O ponto do exercício: depois de estourado o limite, a senha <b>certa</b> também é
     * recusada — e tem de ser. Se a senha certa passasse, o atacante continuaria podendo testar
     * senhas à vontade, que é exatamente o que o limite existe para impedir. A recusa é 429 e
     * não 401: a requisição nem chegou a ser avaliada.
     */
    @Test
    void depoisDoLimiteAteASenhaCertaEhRecusadaComoLimite() throws Exception {
        for (int i = 0; i < 3; i++) {
            tentar(EMAIL, "senha-errada", "10.0.1.1").andExpect(status().isUnauthorized());
        }

        tentar(EMAIL, SENHA, "10.0.1.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("LIMITE_DE_TENTATIVAS"));
    }

    /**
     * Quem acertou a senha provou ser dono da conta: as tentativas erradas anteriores foram dedo
     * trocado, e o contador do e-mail zera. Sem isso, duas senhas erradas seguidas de uma certa
     * deixariam a pessoa a um erro de distância do bloqueio pelo resto da janela.
     */
    @Test
    void loginCertoZeraOContadorDoEmail() throws Exception {
        tentar(EMAIL, "senha-errada", "10.0.2.1").andExpect(status().isUnauthorized());
        tentar(EMAIL, "senha-errada", "10.0.2.2").andExpect(status().isUnauthorized());

        tentar(EMAIL, SENHA, "10.0.2.3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // Zerado: as três tentativas seguintes voltam a ser 401, não 429. Cada uma de uma origem
        // diferente de propósito — o contador por origem NÃO zera com o acerto (ver o teste
        // abaixo), e reaproveitar um IP aqui faria este teste falhar pelo motivo errado.
        for (int i = 4; i < 7; i++) {
            tentar(EMAIL, "senha-errada", "10.0.2." + i).andExpect(status().isUnauthorized());
        }
    }

    /**
     * ⚠️ O acerto zera o contador do e-mail e <b>não</b> o da origem, e a assimetria é
     * deliberada. Quem acertou a senha provou ser dono daquela conta — mas não provou nada sobre
     * a origem, que pode ser um atacante varrendo contas e que acertou uma. Zerar ali lhe
     * devolveria a janela inteira a cada acerto, e o limite por origem viraria decoração.
     * <p>
     * O custo aceito: numa rede com muita gente atrás do mesmo IP, os erros de senha de todos
     * somam. É por isso que o limite por origem é folgado (60 em quinze minutos no padrão), e
     * quem protege a conta é o limite por e-mail.
     */
    @Test
    void acertoNaoZeraOContadorDaOrigem() throws Exception {
        for (int i = 0; i < 3; i++) {
            tentar("outra.pessoa" + i + "@conectsol.com", "senha-errada", "10.0.6.1")
                    .andExpect(status().isUnauthorized());
        }

        tentar(EMAIL, SENHA, "10.0.6.1").andExpect(status().isOk());

        // Quarta falha na mesma origem: o limite por origem (4) é atingido apesar do acerto.
        tentar("outra.pessoa9@conectsol.com", "senha-errada", "10.0.6.1")
                .andExpect(status().isUnauthorized());
        tentar("outra.pessoa8@conectsol.com", "senha-errada", "10.0.6.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("LIMITE_DE_TENTATIVAS"));
    }

    /**
     * O limite por origem existe para conter a enxurrada, e por isso conta mesmo quando o e-mail
     * muda a cada tentativa — que é o formato de um ataque de enumeração, e o que gastaria o
     * BCrypt sem nunca estourar o contador por conta.
     */
    @Test
    void limitePorOrigemContaMesmoComEmailsDiferentes() throws Exception {
        for (int i = 0; i < 4; i++) {
            tentar("ninguem" + i + "@conectsol.com", "senha-errada", "10.0.3.7")
                    .andExpect(status().isUnauthorized());
        }

        tentar("ninguem99@conectsol.com", "senha-errada", "10.0.3.7")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("LIMITE_DE_TENTATIVAS"));
    }

    /**
     * O contador do e-mail ignora a caixa, como o próprio login — senão o limite por conta seria
     * contornável só alternando maiúsculas.
     */
    @Test
    void contadorDeEmailIgnoraACaixa() throws Exception {
        for (int i = 0; i < 3; i++) {
            tentar(EMAIL, "senha-errada", "10.0.4." + i).andExpect(status().isUnauthorized());
        }

        tentar(EMAIL.toUpperCase(), "senha-errada", "10.0.4.90")
                .andExpect(status().isTooManyRequests());
    }

    /**
     * {@code X-Forwarded-For} só vale com {@code confiar-em-proxy=true}, que aqui está desligado.
     * Se fosse lido sem proxy na frente, qualquer um trocaria de origem a cada requisição
     * forjando o cabeçalho, e o limite por origem deixaria de existir.
     */
    @Test
    void naoConfiaNoCabecalhoDeProxyQuandoNaoConfigurado() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", "203.0.113." + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(corpo("forjado" + i + "@conectsol.com", "senha-errada")))
                    .andExpect(status().isUnauthorized());
        }

        // Mesma conexão, apesar do cabeçalho variado: a origem continua sendo a real.
        mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "203.0.113.200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("forjado99@conectsol.com", "senha-errada")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void retryAfterVemEmSegundosEDentroDaJanela() throws Exception {
        for (int i = 0; i < 3; i++) {
            tentar(EMAIL, "senha-errada", "10.0.5.1").andExpect(status().isUnauthorized());
        }

        String segundos = tentar(EMAIL, "senha-errada", "10.0.5.1")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getHeader("Retry-After");

        assertThat(segundos).isNotNull();
        assertThat(Integer.parseInt(segundos)).isPositive().isLessThanOrEqualTo(15 * 60);
    }

    private ResultActions tentar(String email, String senha, String origem) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(requisicao -> {
                    requisicao.setRemoteAddr(origem);
                    return requisicao;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo(email, senha)));
    }

    private static String corpo(String email, String senha) {
        return """
                {"email":"%s","senha":"%s"}""".formatted(email, senha);
    }
}
