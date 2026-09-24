package com.conectsol.solarsync.cliente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.conectsol.solarsync.auth.NomePapel;
import com.conectsol.solarsync.auth.PapelRepository;
import com.conectsol.solarsync.auth.Usuario;
import com.conectsol.solarsync.auth.UsuarioRepository;
import com.conectsol.solarsync.common.AbstractIntegrationTest;
import com.conectsol.solarsync.debito.DebitoRepository;
import com.conectsol.solarsync.historico.HistoricoStatusRepository;
import com.conectsol.solarsync.pendencia.PendenciaRepository;
import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.jayway.jsonpath.JsonPath;

/**
 * A prioridade de cliente, ponta a ponta: quem sobe na fila, o que a data de instalação faz
 * quando o motivo é a instalação adiantada, e o que acontece quando a prioridade acaba.
 * <p>
 * Sem {@code @Transactional} e com limpeza no {@code @AfterEach}, como os outros testes de
 * integração: há listener em {@code AFTER_COMMIT} no caminho (a triagem sem pendência cria o
 * projeto).
 */
@AutoConfigureMockMvc
class PrioridadeClienteHttpTest extends AbstractIntegrationTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String EMAIL = "analista.prioridade@conectsol.com";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PapelRepository papelRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private ProjetoRepository projetoRepository;

    @Autowired
    private PendenciaRepository pendenciaRepository;

    @Autowired
    private DebitoRepository debitoRepository;

    @Autowired
    private HistoricoStatusRepository historicoStatusRepository;

    private Long usuarioCriadoId;
    private String token;

    @BeforeEach
    void autenticar() throws Exception {
        Usuario analista = Usuario.builder()
                .nome("Analista da Prioridade")
                .email(EMAIL)
                .ativo(true)
                .senhaHash(passwordEncoder.encode(SENHA))
                .papeis(Set.of(papelRepository.findByNome(NomePapel.ANALISTA).orElseThrow()))
                .build();
        usuarioCriadoId = usuarioRepository.save(analista).getId();

        String login = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "senha": "%s"}""".formatted(EMAIL, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(login, "$.accessToken");
    }

    /**
     * ⚠️ O cliente sai <b>antes</b> do usuário, ao contrário dos outros testes de integração:
     * {@code cliente.prioridade_definida_por_id} tem FK para {@code usuario}, então apagar quem
     * pediu a prioridade antes do cliente falha com violação de chave estrangeira. É a mesma
     * armadilha que {@code historico_status.usuario_id} já criava, agora numa segunda tabela.
     */
    @AfterEach
    void limpar() {
        historicoStatusRepository.deleteAll();
        debitoRepository.deleteAll();
        projetoRepository.deleteAll();
        pendenciaRepository.deleteAll();
        clienteRepository.deleteAll();
        if (usuarioCriadoId != null) {
            usuarioRepository.deleteById(usuarioCriadoId);
            usuarioCriadoId = null;
        }
    }

    @Test
    void clienteComumNasceSemPrioridade() throws Exception {
        int id = criarCliente("Cliente Comum");

        mvc.perform(autenticada(get("/api/clientes/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prioridade").value(false))
                .andExpect(jsonPath("$.prioridadeMotivo").doesNotExist())
                .andExpect(jsonPath("$.etiquetas").isEmpty());
    }

    /**
     * O pedido de prioridade guarda quem pediu e quando. É a informação que responde "isto ainda
     * vale?" duas semanas depois, e não há como recuperá-la se não for gravada na hora.
     */
    @Test
    void prioridadePorPrazoNaoExigeDataEGuardaQuemPediu() throws Exception {
        int id = criarCliente("Escola com Prazo");

        requisicao(post("/api/clientes/%d/prioridade".formatted(id)), """
                {"motivo": "PRAZO_CONTRATUAL", "observacao": "Contrato prevê 30 dias"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prioridade").value(true))
                .andExpect(jsonPath("$.prioridadeMotivo").value("PRAZO_CONTRATUAL"))
                .andExpect(jsonPath("$.prioridadeObservacao").value("Contrato prevê 30 dias"))
                .andExpect(jsonPath("$.prioridadeDefinidaEm").isNotEmpty())
                .andExpect(jsonPath("$.prioridadeDefinidaPor.id").value(usuarioCriadoId.intValue()))
                // Motivo que não é de instalação não carrega data: deixá-la faria o projeto
                // seguinte nascer "instalado" por causa de uma prioridade que mudou de razão.
                .andExpect(jsonPath("$.prioridadeDataInstalacao").doesNotExist());
    }

    /**
     * A data é exigida só neste motivo, e a recusa tem código próprio — ela não é burocracia: é o
     * que a etapa de vistoria vai usar, e aceitar o pedido sem ela guardaria uma prioridade que
     * não se completa.
     */
    @Test
    void prioridadePorInstalacaoSemDataEhRecusada() throws Exception {
        int id = criarCliente("Cliente Instalado");

        requisicao(post("/api/clientes/%d/prioridade".formatted(id)), """
                {"motivo": "INSTALACAO_ADIANTADA"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PRIORIDADE_SEM_INSTALACAO"));

        mvc.perform(autenticada(get("/api/clientes/" + id)))
                .andExpect(jsonPath("$.prioridade").value(false));
    }

    /**
     * O caso central do requisito: a data informada na triagem <b>desce para o projeto</b> — e é
     * de lá que a etapa de vistoria a lê. Não há segundo campo de data de instalação; há um
     * campo, alimentado por dois caminhos.
     * <p>
     * Aqui o projeto ainda não existe quando a prioridade é pedida, que é o caso normal: a
     * prioridade é marcada na triagem. O projeto nasce depois, já sabendo da data.
     */
    @Test
    void prioridadePorInstalacaoDesceParaOProjetoQueNasceDepois() throws Exception {
        int id = criarCliente("Usina Montada");
        LocalDate instalacao = LocalDate.now().minusDays(5);

        requisicao(post("/api/clientes/%d/prioridade".formatted(id)), """
                {"motivo": "INSTALACAO_ADIANTADA", "dataInstalacao": "%s"}"""
                .formatted(instalacao))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prioridadeDataInstalacao").value(instalacao.toString()));

        // A triagem sem pendência cria o projeto (listener AFTER_COMMIT).
        requisicao(post("/api/clientes/%d/sem-pendencia".formatted(id)), "")
                .andExpect(status().isOk());

        Projeto projeto = umProjetoDoCliente(id);
        assertThat(projeto.getDataInstalacao())
                .as("o projeto nasce com a data que a prioridade declarou")
                .isEqualTo(instalacao);
    }

    /** O outro caminho: o projeto já existia, e a prioridade o alcança na hora. */
    @Test
    void prioridadePorInstalacaoAlcancaProjetoQueJaExistia() throws Exception {
        int id = criarCliente("Já com Projeto");
        requisicao(post("/api/projetos"), """
                {"clienteId": %d, "tipoProjeto": "PROJETO_INICIAL"}""".formatted(id))
                .andExpect(status().isCreated());

        LocalDate instalacao = LocalDate.now().minusDays(2);
        requisicao(post("/api/clientes/%d/prioridade".formatted(id)), """
                {"motivo": "INSTALACAO_ADIANTADA", "dataInstalacao": "%s"}"""
                .formatted(instalacao))
                .andExpect(status().isOk());

        assertThat(umProjetoDoCliente(id).getDataInstalacao()).isEqualTo(instalacao);
    }

    /**
     * A data registrada na etapa de vistoria não é sobrescrita por um pedido de prioridade: quem
     * instalou de fato e anotou sabe mais que um pedido que pode ser de semanas atrás.
     */
    @Test
    void prioridadeNaoSobrescreveInstalacaoJaRegistrada() throws Exception {
        int id = criarCliente("Instalação Já Anotada");
        String projeto = requisicao(post("/api/projetos"), """
                {"clienteId": %d, "tipoProjeto": "PROJETO_INICIAL"}""".formatted(id))
                .andReturn().getResponse().getContentAsString();
        int projetoId = JsonPath.read(projeto, "$.id");

        LocalDate real = LocalDate.now().minusDays(10);
        requisicao(post("/api/projetos/%d/registrar-instalacao".formatted(projetoId)), """
                {"dataInstalacao": "%s"}""".formatted(real))
                .andExpect(status().isOk());

        requisicao(post("/api/clientes/%d/prioridade".formatted(id)), """
                {"motivo": "INSTALACAO_ADIANTADA", "dataInstalacao": "%s"}"""
                .formatted(LocalDate.now()))
                .andExpect(status().isOk());

        assertThat(umProjetoDoCliente(id).getDataInstalacao()).isEqualTo(real);
    }

    /**
     * O requisito de posicionamento: o prioritário vem no topo qualquer que seja a ordenação
     * pedida.
     * <p>
     * O prioritário é o do <b>meio</b> do alfabeto de propósito. Fosse o primeiro ou o último, uma
     * das duas direções o colocaria no topo sozinha, e o teste passaria sem a regra existir.
     */
    @Test
    void oClientePrioritarioVemNoTopoDaListagemNasDuasDirecoes() throws Exception {
        criarCliente("Alfa Primeiro");
        int meio = criarCliente("Beta Segundo");
        criarCliente("Zulu Último");

        requisicao(post("/api/clientes/%d/prioridade".formatted(meio)), """
                {"motivo": "PRAZO_DO_CLIENTE"}""")
                .andExpect(status().isOk());

        mvc.perform(autenticada(get("/api/clientes?sort=nome,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo[0].nome").value("Beta Segundo"))
                .andExpect(jsonPath("$.conteudo[0].prioridade").value(true))
                // Dentro do grupo dos normais, a ordenação pedida continua valendo.
                .andExpect(jsonPath("$.conteudo[1].nome").value("Alfa Primeiro"))
                .andExpect(jsonPath("$.conteudo[2].nome").value("Zulu Último"));

        mvc.perform(autenticada(get("/api/clientes?sort=nome,desc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo[0].nome").value("Beta Segundo"))
                .andExpect(jsonPath("$.conteudo[1].nome").value("Zulu Último"))
                .andExpect(jsonPath("$.conteudo[2].nome").value("Alfa Primeiro"));
    }

    /**
     * O prioritário continua no topo quando avança de etapa — é o que o requisito pede
     * ("depois que a etapa for concluída e o projeto avançar, ele deve continuar sendo tratado
     * como prioridade"). Nada é remarcado: a prioridade é do cliente, não da etapa.
     */
    @Test
    void aPrioridadeAcompanhaOClienteParaAEtapaSeguinte() throws Exception {
        int comum = criarCliente("Alfa Comum");
        int prioritario = criarCliente("Zulu Prioritário");

        requisicao(post("/api/clientes/%d/prioridade".formatted(prioritario)), """
                {"motivo": "PRAZO_CONTRATUAL"}""")
                .andExpect(status().isOk());

        for (int cliente : new int[] { comum, prioritario }) {
            requisicao(post("/api/projetos"), """
                    {"clienteId": %d, "tipoProjeto": "PROJETO_INICIAL"}""".formatted(cliente))
                    .andExpect(status().isCreated());
        }

        mvc.perform(autenticada(get("/api/projetos?sort=id,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo[0].clienteNome").value("Zulu Prioritário"))
                .andExpect(jsonPath("$.conteudo[0].clientePrioritario").value(true))
                .andExpect(jsonPath("$.conteudo[1].clienteNome").value("Alfa Comum"));
    }

    /**
     * Encerrada a prioridade, o cliente volta à ordem normal — mas a data de instalação já
     * registrada no projeto <b>fica</b>: ela é fato de campo, não privilégio, e apagá-la travaria
     * a solicitação da vistoria mais adiante.
     */
    @Test
    void removerPrioridadeVoltaAOrdemNormalESemApagarAInstalacao() throws Exception {
        criarCliente("Alfa Comum");
        int prioritario = criarCliente("Zulu Prioritário");

        LocalDate instalacao = LocalDate.now().minusDays(3);
        requisicao(post("/api/clientes/%d/prioridade".formatted(prioritario)), """
                {"motivo": "INSTALACAO_ADIANTADA", "dataInstalacao": "%s"}"""
                .formatted(instalacao))
                .andExpect(status().isOk());
        requisicao(post("/api/clientes/%d/sem-pendencia".formatted(prioritario)), "")
                .andExpect(status().isOk());

        requisicao(post("/api/clientes/%d/remover-prioridade".formatted(prioritario)), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prioridade").value(false))
                .andExpect(jsonPath("$.prioridadeMotivo").doesNotExist())
                .andExpect(jsonPath("$.prioridadeDataInstalacao").doesNotExist());

        mvc.perform(autenticada(get("/api/clientes?sort=nome,asc")))
                .andExpect(jsonPath("$.conteudo[0].nome").value("Alfa Comum"));

        assertThat(umProjetoDoCliente(prioritario).getDataInstalacao()).isEqualTo(instalacao);
    }

    /**
     * Corrigir o cadastro não pode apagar a prioridade de passagem — a mesma disciplina que já
     * vale para o {@code statusTriagem}: estado do fluxo muda por endpoint de ação, não pelo PUT.
     */
    @Test
    void oPutDoCadastroNaoMexeNaPrioridade() throws Exception {
        int id = criarCliente("Cliente Editado");
        requisicao(post("/api/clientes/%d/prioridade".formatted(id)), """
                {"motivo": "OUTRO", "observacao": "Caso excepcional"}""")
                .andExpect(status().isOk());

        requisicao(put("/api/clientes/" + id), """
                {"nome": "Cliente Editado", "telefone": "(71) 90000-0000"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prioridade").value(true))
                .andExpect(jsonPath("$.prioridadeMotivo").value("OUTRO"));
    }

    private int criarCliente(String nome) throws Exception {
        String corpo = requisicao(post("/api/clientes"), """
                {"nome": "%s", "cidade": "Salvador"}""".formatted(nome))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.id");
    }

    private Projeto umProjetoDoCliente(int clienteId) {
        var projetos = projetoRepository.findByClienteId((long) clienteId);
        assertThat(projetos).hasSize(1);
        return projetos.get(0);
    }

    private ResultActions requisicao(MockHttpServletRequestBuilder builder, String corpo)
            throws Exception {
        return mvc.perform(builder
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    private ResultActions requisicao(MockHttpServletRequestBuilder builder) throws Exception {
        return requisicao(builder, "");
    }

    private MockHttpServletRequestBuilder autenticada(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
