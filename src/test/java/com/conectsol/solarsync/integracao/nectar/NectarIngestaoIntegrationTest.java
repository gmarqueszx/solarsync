package com.conectsol.solarsync.integracao.nectar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.conectsol.solarsync.cliente.Cliente;
import com.conectsol.solarsync.cliente.ClienteRepository;
import com.conectsol.solarsync.cliente.EtiquetaCliente;
import com.conectsol.solarsync.cliente.StatusTriagem;
import com.conectsol.solarsync.common.AbstractIntegrationTest;
import com.conectsol.solarsync.projeto.ProjetoRepository;

/**
 * Prova da entrada automática de clientes pelo CRM (seção 9 do CLAUDE.md), sem chamar o Nectar:
 * o teste entra pela ingestão, que recebe a oportunidade já desserializada. É por isso que
 * {@link OportunidadeNectar} é um record simples — dá para montá-lo à mão.
 * <p>
 * As oportunidades usadas aqui são <b>dados reais</b> da carteira da ConectSol, lidos da API em
 * 17/09/2026.
 */
class NectarIngestaoIntegrationTest extends AbstractIntegrationTest {

    private static final String CAMPO_PAGAMENTO = "Data do Pagamento";

    @Autowired
    private NectarIngestaoService ingestaoService;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private ProjetoRepository projetoRepository;

    @AfterEach
    void limpar() {
        projetoRepository.deleteAll();
        clienteRepository.deleteAll();
    }

    @Test
    void oportunidadeValidadaCriaClienteNaFilaDaTriagemESemProjeto() {
        assertThat(ingestaoService.ingerir(hudson())).isTrue();

        Cliente cliente = umCliente();
        assertThat(cliente.getNome()).isEqualTo("HUDSON OLIVEIRA SOUZA");
        // Cidade e vendedor saem no padrão do cadastro, não como o CRM os escreveu: o nome da
        // oportunidade traz "VITÓRIA DA CONQUISTA" e o responsável é "Rodrigo soares".
        assertThat(cliente.getCidade()).isEqualTo("Vitória da Conquista");
        assertThat(cliente.getVendedor()).isEqualTo("Rodrigo");
        assertThat(cliente.getTelefone()).isEqualTo("+5577999295821");
        assertThat(cliente.getDataPagamento()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(cliente.getNectarOportunidadeId()).isEqualTo("29839213");

        // Entra na fila da triagem, e não com projeto pronto: o CRM não sabe se há pendência na
        // Coelba, e criar o projeto aqui faria o cliente sair da fila como se alguém já tivesse
        // checado — a confusão que o StatusTriagem existe para desfazer.
        assertThat(cliente.getStatusTriagem()).isEqualTo(StatusTriagem.AGUARDANDO_VERIFICACAO);
        assertThat(projetoRepository.findByClienteId(cliente.getId())).isEmpty();

        // A UC nasce nula de propósito: é descoberta justamente na checagem da etapa 1, por quem
        // consulta a agência virtual — e não existe no CRM.
        assertThat(cliente.getUcCoelba()).isNull();
    }

    /**
     * A idempotência que sustenta o polling: o job relê as mesmas páginas do CRM a cada dez
     * minutos, então reimportar tem de ser um não-evento.
     */
    @Test
    void reimportarAMesmaOportunidadeNaoCriaSegundoCliente() {
        assertThat(ingestaoService.ingerir(hudson())).isTrue();
        assertThat(ingestaoService.ingerir(hudson())).isFalse();

        assertThat(clienteRepository.findAll()).hasSize(1);
    }

    /**
     * Só cria, nunca atualiza: a analista corrige nome, cidade e telefone na triagem — e a
     * cidade vem de uma convenção de nome, então correção acontece —, e deixar o CRM
     * sobrescrever isso a cada dez minutos desfaria o trabalho dela.
     */
    @Test
    void reimportarNaoSobrescreveCorrecaoFeitaNoSolarSync() {
        ingestaoService.ingerir(hudson());

        Cliente corrigido = umCliente();
        corrigido.setCidade("Barra do Choça");
        corrigido.setUcCoelba("1234567890");
        clienteRepository.save(corrigido);

        ingestaoService.ingerir(hudson());

        Cliente depois = umCliente();
        assertThat(depois.getCidade()).isEqualTo("Barra do Choça");
        assertThat(depois.getUcCoelba()).isEqualTo("1234567890");
    }

    /**
     * O padrão do cadastro vale também para o que vem do CRM. Sem isto, a lista de clientes
     * ficava com "CACULE", "VITÓRIA DA CONQUISTA", "Vitória Da Conquista" e "Brumado" como
     * cidades diferentes, e com vendedores fora da lista de seleção da tela.
     */
    @Test
    void cidadeEVendedorSaemNoPadraoDoCadastro() {
        ingestaoService.ingerir(joseNeri(29396646L));

        Cliente cliente = umCliente();
        assertThat(cliente.getCidade()).isEqualTo("Caculé");
        assertThat(cliente.getVendedor()).isEqualTo("Judson");
    }

    /**
     * Quem não está na lista entra <b>nulo</b>, para ser escolhido na triagem. Além dos
     * vendedores, o campo "responsável" do Nectar carrega gente do administrativo — a importação
     * real trouxe "Thainara Gomes" e "Evelin Barros", que não são vendedoras —, e adivinhar que
     * são poluiria o campo. Mesma coisa para cidade que não é município da Bahia.
     */
    @Test
    void valorForaDaListaEntraNuloEmVezDeTextoLivre() {
        OportunidadeNectar foraDoPadrao = new OportunidadeNectar(29405196L,
                "Dener Cesário Silva Machado_Pindobeira_Thainara_R$ 2.000,00",
                "VALIDADO PELO FINANCEIRO", funil(),
                new OportunidadeNectar.Pessoa(1L, "Dener Cesário Silva Machado", null, null, null),
                null,
                new OportunidadeNectar.Responsavel(2L, "Thainara Gomes"),
                "2026-05-21T21:29:17.894Z", Map.of());

        assertThat(ingestaoService.ingerir(foraDoPadrao)).isTrue();

        Cliente cliente = umCliente();
        assertThat(cliente.getNome()).isEqualTo("Dener Cesário Silva Machado");
        // "Pindobeira" não é município da Bahia e "Thainara" não está na lista de vendedores.
        assertThat(cliente.getCidade()).isNull();
        assertThat(cliente.getVendedor()).isNull();
    }

    /**
     * Quatro oportunidades reais do mesmo cliente (JOSE NERI, "PROJETO 2" a "PROJETO 5"): a
     * idempotência é por <b>oportunidade</b>, não por pessoa, então entram quatro clientes.
     * <p>
     * É deliberado e é o que preserva o trabalho: cada oportunidade é uma usina distinta, com UC
     * e pendência próprias, e a planilha que o SolarSync substitui também tinha uma linha por
     * projeto. Deduplicar por pessoa faria três das quatro desaparecerem sem ninguém notar.
     * O efeito colateral — o mesmo nome repetido na lista de clientes — está registrado nos
     * buracos conhecidos da seção 11.
     */
    @Test
    void oportunidadesDiferentesDoMesmoClienteEntramSeparadas() {
        for (long id : new long[] { 29396646L, 29396647L, 29396658L, 29396659L }) {
            assertThat(ingestaoService.ingerir(joseNeri(id))).isTrue();
        }

        List<Cliente> clientes = clienteRepository.findAll();
        assertThat(clientes).hasSize(4);
        assertThat(clientes).allSatisfy(cliente -> {
            assertThat(cliente.getNome()).isEqualTo("JOSE NERI SANTIAGO FILHO DE CACULÉ");
            assertThat(cliente.getCidade()).isEqualTo("Caculé");
        });
        assertThat(clientes).extracting(Cliente::getNectarOportunidadeId)
                .containsExactlyInAnyOrder("29396646", "29396647", "29396658", "29396659");
    }

    /**
     * Sem o campo "Data do Pagamento" preenchido (o caso de pouco menos da metade da carteira),
     * a entrada na etapa serve de marco — e não a {@code dataCriacao}, que vem corrompida.
     */
    @Test
    void semDataDePagamentoUsaAEntradaNaEtapa() {
        ingestaoService.ingerir(joseNeri(29396646L));

        assertThat(umCliente().getDataPagamento()).isEqualTo(LocalDate.of(2026, 5, 21));
    }

    /**
     * Sem id não há como garantir que a oportunidade não volta na próxima execução, então
     * importá-la criaria um cliente novo a cada dez minutos.
     */
    @Test
    void oportunidadeSemIdEhRecusada() {
        assertThat(ingestaoService.ingerir(new OportunidadeNectar(null, "Venda_Brumado_Deilson",
                "VALIDADO PELO FINANCEIRO", funil(), null, null, null, null, null))).isFalse();

        assertThat(clienteRepository.findAll()).isEmpty();
    }

    /**
     * Cliente anônimo é pior que cliente ausente: o nome é a chave de busca de toda a operação, e
     * uma linha em branco na fila da triagem não diz a ninguém o que fazer. É também o sinal de
     * que {@link OportunidadeNectar} pode estar mapeando o campo errado — daí o WARN no log.
     */
    @Test
    void oportunidadeSemNomeUtilizavelEhRecusada() {
        assertThat(ingestaoService.ingerir(new OportunidadeNectar(106L, null,
                "VALIDADO PELO FINANCEIRO", funil(), null, null, null, null, null))).isFalse();

        assertThat(clienteRepository.findAll()).isEmpty();
    }

    /**
     * Campo mais longo que a coluna não pode fazer a sincronização inteira parar. Não é hipótese:
     * a oportunidade real 29553717 tem o título inteiro colado no nome do cliente, com 137
     * caracteres — e existe pior.
     */
    @Test
    void nomeMaiorQueAColunaEhTruncadoEmVezDeQuebrarOJob() {
        OportunidadeNectar nomeEnorme = new OportunidadeNectar(29553717L, "X_Brumado_Judson",
                "VALIDADO PELO FINANCEIRO", funil(),
                new OportunidadeNectar.Pessoa(1L, "N".repeat(400), null, null, null),
                null, null, null, null);

        assertThat(ingestaoService.ingerir(nomeEnorme)).isTrue();
        assertThat(umCliente().getNome()).hasSize(150);
    }

    /**
     * A etiqueta "Banco" sai da <b>etapa de entrada</b> por onde a oportunidade chegou, e não de
     * um campo do CRM: a etapa "ADIANTAR PROJETO COELBA PARA BANCO OU VENDEDOR" é a porta dos
     * projetos financiados. Precisa virar dado do cliente aqui porque a etapa de origem não
     * sobrevive à importação — a oportunidade segue andando no Nectar.
     */
    @Test
    void oportunidadeDaEtapaDeBancoMarcaOClienteComoBanco() {
        ingestaoService.ingerir(joseNeri(29396646L));

        Cliente cliente = umCliente();
        assertThat(cliente.isBanco()).isTrue();
        // As duas etiquetas, e sem repetição: derivadas, não armazenadas.
        assertThat(EtiquetaCliente.de(cliente))
                .containsExactly(EtiquetaCliente.CRM, EtiquetaCliente.BANCO);
    }

    /** A outra porta — "5- Financeiro" — é a do cliente que paga do próprio bolso. */
    @Test
    void oportunidadeDaEtapaNormalNaoVemMarcadaComoBanco() {
        ingestaoService.ingerir(hudson());

        Cliente cliente = umCliente();
        assertThat(cliente.isBanco()).isFalse();
        assertThat(EtiquetaCliente.de(cliente)).containsExactly(EtiquetaCliente.CRM);
    }

    /**
     * Reimportar não duplica etiqueta porque não há etiqueta para duplicar: elas são derivadas a
     * cada leitura. É o que torna o requisito "não duplicar etiquetas caso a operação seja
     * executada novamente" impossível de violar, em vez de dependente de uma checagem.
     */
    @Test
    void reimportarNaoDuplicaEtiquetas() {
        ingestaoService.ingerir(joseNeri(29396646L));
        ingestaoService.ingerir(joseNeri(29396646L));

        assertThat(EtiquetaCliente.de(umCliente()))
                .containsExactly(EtiquetaCliente.CRM, EtiquetaCliente.BANCO);
    }

    /** Oportunidade real 29839213, funil "5- Financeiro", em VALIDADO PELO FINANCEIRO. */
    private static OportunidadeNectar hudson() {
        return new OportunidadeNectar(
                29839213L,
                "HUDSON OLIVEIRA SOUZA_VITÓRIA DA CONQUISTA_RODRIGO_3X + R$ 666,67 + 48X + "
                        + "R$ 526,40_380/220V",
                "VALIDADO PELO FINANCEIRO",
                funil(),
                new OportunidadeNectar.Pessoa(57066424L, "HUDSON OLIVEIRA SOUZA ",
                        "+5577999295821", "+5577999295821", "hudson@gmail.com"),
                new OportunidadeNectar.Pessoa(57066424L, "HUDSON OLIVEIRA SOUZA ", null, null,
                        null),
                new OportunidadeNectar.Responsavel(175661L, "Rodrigo soares"),
                "2026-09-03T11:39:31.825Z",
                Map.of(CAMPO_PAGAMENTO, "02/09/2026"));
    }

    /** As quatro oportunidades reais do mesmo cliente, no funil "4- Nota Fiscal". */
    private static OportunidadeNectar joseNeri(long id) {
        return new OportunidadeNectar(
                id,
                "(PROJETO " + id + ")JOSE NERI SANTIAGO FILHO DE CACULE_CACULE_JUDSON_"
                        + "35.000(BANCO)_220/380",
                "ADIANTAR PROJETO COELBA PARA BANCO OU VENDEDOR",
                new OportunidadeNectar.FunilVenda(58574L, "4- Nota Fiscal"),
                new OportunidadeNectar.Pessoa(56437802L, "JOSE NERI SANTIAGO FILHO DE CACULÉ",
                        "+5577981341548", "+5577981341548", "77981341548@nandb"),
                null,
                new OportunidadeNectar.Responsavel(175659L, "Judson Rocha"),
                "2026-05-21T17:36:15.313Z",
                Map.of());
    }

    private static OportunidadeNectar.FunilVenda funil() {
        return new OportunidadeNectar.FunilVenda(58569L, "5- Financeiro");
    }

    private Cliente umCliente() {
        List<Cliente> clientes = clienteRepository.findAll();
        assertThat(clientes).hasSize(1);
        return clientes.get(0);
    }
}
