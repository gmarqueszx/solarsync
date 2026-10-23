package com.conectsol.solarsync.integracao.gmail;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.conectsol.solarsync.projeto.ProjetoRepository;

import lombok.RequiredArgsConstructor;

/**
 * Lê periodicamente a caixa de e-mail e aplica os retornos da Coelba. É a etapa 3 do fluxo
 * automatizada: onde antes alguém abria o e-mail diário e atualizava a planilha, agora o status
 * do projeto muda sozinho.
 * <p>
 * O bean só existe com {@code solarsync.gmail.ativo=true}. Desligado, não há nada agendado — o
 * que mantém testes e desenvolvimento local sem chamada externa nenhuma.
 * <p>
 * Com {@code solarsync.gmail.somente-conferencia=true} o job faz tudo menos agir: lê, casa com
 * o projeto, grava o que entendeu em {@code email_coelba} e não muda status nenhum. É o ensaio
 * que a seção 9 do CLAUDE.md pede antes de ligar a integração para valer, já que a redação real
 * do e-mail da Coelba nunca foi observada.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "solarsync.gmail", name = "ativo", havingValue = "true")
class RetornoCoelbaJob {

    private static final Logger log = LoggerFactory.getLogger(RetornoCoelbaJob.class);

    /**
     * Por quanto tempo depois do envio o projeto ganha a busca sem limite de data. Um dia cobre
     * com folga um Gmail fora do ar ou um deploy no meio; depois disso a consulta normal, de sete
     * dias, já alcança tudo o que chegar.
     */
    static final Duration JANELA_DA_BUSCA_RETROATIVA = Duration.ofHours(24);

    private final GmailClient gmailClient;
    private final EmailCoelbaRepository emailCoelbaRepository;
    private final RetornoCoelbaService retornoCoelbaService;
    private final GmailProperties propriedades;
    private final ProjetoRepository projetoRepository;

    /**
     * {@code fixedDelay} conta o intervalo a partir do fim da execução anterior, então uma
     * leitura lenta não enfileira outra em cima — importante aqui porque duas execuções
     * concorrentes tentariam aplicar o mesmo e-mail.
     */
    @Scheduled(
            fixedDelayString = "${solarsync.gmail.intervalo:PT15M}",
            initialDelayString = "PT45S")
    void lerRetornos() {
        List<String> ids;
        Set<String> somenteVistoria;
        try {
            // ⚠️ A busca retroativa vem PRIMEIRO na lista. Cada listagem do Gmail sai do mais novo
            // para o mais antigo, e a inversão lá embaixo é o que põe tudo em ordem cronológica;
            // com a retroativa na frente, a deduplicação descarta a cópia da consulta normal e
            // os e-mails de cada número continuam juntos e na ordem certa depois da inversão.
            Set<String> todos = new LinkedHashSet<>(buscaRetroativa());
            List<String> daVistoria = buscaRetroativaDaVistoria();
            Set<String> normais = new HashSet<>(todos);
            todos.addAll(daVistoria);
            List<String> daConsulta;
            if (propriedades.somenteProjetosConhecidos()) {
                List<String> numeros = projetoRepository.numerosAguardandoRetornoDaCoelba();
                // Em DEBUG e não em INFO: em regime é a mesma informação a cada quinze minutos.
                // Mas é a primeira coisa a olhar quando "a integração parou de funcionar" —
                // lista vazia significa que nenhum projeto está esperando retorno, e aí não há
                // e-mail a buscar, o que é diferente de a caixa estar vazia.
                log.debug("Gmail: buscando retorno de {} projeto(s) aguardando", numeros.size());
                daConsulta = gmailClient.listarIdsDosProjetos(numeros);
            } else {
                daConsulta = gmailClient.listarIds();
            }
            todos.addAll(daConsulta);
            normais.addAll(daConsulta);
            ids = List.copyOf(todos);
            // Só o que veio exclusivamente da busca da vistoria fica restrito a ela. O que também
            // caiu na consulta normal ou na do envio seria lido por inteiro de qualquer jeito.
            somenteVistoria = new HashSet<>(daVistoria);
            somenteVistoria.removeAll(normais);
        } catch (RuntimeException falha) {
            // Não relança: Gmail fora do ar, refresh token revogado ou cota estourada não podem
            // encerrar o agendamento. Na próxima execução tenta de novo.
            log.error("Falha ao listar mensagens no Gmail. Nada foi processado nesta execução.",
                    falha);
            return;
        }

        if (ids.isEmpty()) {
            log.debug("Gmail: nenhuma mensagem casou com a consulta configurada");
            return;
        }

        // O filtro do Gmail é por data ({@code newer_than}), então as mesmas mensagens voltam a
        // cada execução. Descartar as conhecidas aqui, em uma consulta só, é o que evita uma
        // chamada HTTP por mensagem já processada.
        //
        // No modo conferência não se descarta nada: releitura é exatamente o que se quer, para
        // ajustar os termos em solarsync.coelba, reiniciar e ver o novo veredito sobre os mesmos
        // e-mails sem depender de a Coelba mandar um novo. Custa uma chamada HTTP por mensagem
        // por execução, e o modo é temporário — vale enquanto se está conferindo.
        List<String> novos;
        if (propriedades.somenteConferencia()) {
            novos = ids;
        } else {
            Set<String> conhecidos = new HashSet<>(emailCoelbaRepository.idsJaProcessados(ids));
            novos = ids.stream().filter(id -> !conhecidos.contains(id)).toList();
        }

        if (novos.isEmpty()) {
            log.debug("Gmail: {} mensagem(ns) na consulta, todas já processadas", ids.size());
            return;
        }

        // ⚠️ Do mais ANTIGO para o mais novo, invertendo a ordem do Gmail (que lista do mais
        // recente para o mais antigo). O portal da Neoenergia manda notificações atrasadas em
        // lote e fora de ordem — foram observados dois avisos da mesma solicitação com quatro
        // segundos de diferença, um deles com prazo já vencido. Processando do mais novo para o
        // mais antigo, o último status aplicado seria o do e-mail mais velho, e o projeto (ou a
        // vistoria) terminaria num estado anterior ao real.
        List<String> emOrdemCronologica = new ArrayList<>(novos);
        Collections.reverse(emOrdemCronologica);

        Map<ResultadoProcessamento, Integer> contagem = new EnumMap<>(ResultadoProcessamento.class);
        for (String id : emOrdemCronologica) {
            ResultadoProcessamento resultado = processar(id, somenteVistoria.contains(id));
            contagem.merge(resultado, 1, Integer::sum);
        }

        if (propriedades.somenteConferencia()) {
            // WARN e não INFO: o modo conferência não aplica nada, e ficar ligado por
            // esquecimento é a integração parecendo funcionar sem nunca mudar um projeto.
            log.warn("Coelba por e-mail em MODO CONFERÊNCIA: {} mensagem(ns) lida(s), nenhum "
                    + "status alterado — {}. Confira email_coelba (resultado = CONFERENCIA diz "
                    + "o que teria acontecido) e desligue solarsync.gmail.somente-conferencia "
                    + "quando a leitura estiver certa.", novos.size(), contagem);
            return;
        }

        log.info("Coelba por e-mail: {} mensagem(ns) processada(s), entre novas e as que esperavam o projeto — {}",
                novos.size(), contagem);

        // Chamados à parte e em WARN porque são os que pedem ação de alguém: e-mail não
        // reconhecido normalmente significa que a Coelba mudou a redação e as listas de termos
        // em solarsync.coelba precisam de ajuste, e sem tela é por este log que se descobre.
        int pendentesDeAtencao = contagem.getOrDefault(ResultadoProcessamento.NAO_RECONHECIDO, 0)
                + contagem.getOrDefault(ResultadoProcessamento.AMBIGUO, 0)
                + contagem.getOrDefault(ResultadoProcessamento.TRANSICAO_INVALIDA, 0)
                + contagem.getOrDefault(ResultadoProcessamento.ERRO, 0);
        if (pendentesDeAtencao > 0) {
            log.warn("{} e-mail(s) da Coelba não viraram mudança de status. Consulte "
                    + "email_coelba (coluna resultado e detalhe) para ver o que houve.",
                    pendentesDeAtencao);
        }
    }

    /**
     * Os e-mails, de qualquer data, dos projetos encaminhados nas últimas
     * {@link #JANELA_DA_BUSCA_RETROATIVA}. Cobre o envio registrado com atraso: a Coelba aprovou
     * a solicitação 2610021352 em 07/10, o projeto só foi marcado como encaminhado em 22/10, e a
     * consulta normal, de sete dias, nunca viu a aprovação (22/10/2026).
     * <p>
     * Aqui no job, e não num listener do envio, por três razões: o clique em "Encaminhar" não
     * espera o Gmail; uma falha do Gmail naquele minuto não perde a busca, porque ela se repete a
     * cada execução dentro da janela; e não há duas leituras concorrentes tentando gravar o mesmo
     * e-mail em {@code email_coelba}. O número sai da lista assim que o projeto deixa
     * {@code ENCAMINHADO}, então a repetição acaba quando o retorno é aplicado.
     */
    private List<String> buscaRetroativa() {
        List<String> numeros = projetoRepository.numerosEncaminhadosDesde(
                Instant.now().minus(JANELA_DA_BUSCA_RETROATIVA));
        if (numeros.isEmpty()) {
            return List.of();
        }
        log.debug("Gmail: busca sem limite de data para {} projeto(s) encaminhado(s) há pouco",
                numeros.size());
        return gmailClient.listarIdsSemLimiteDeData(numeros);
    }

    /**
     * Os e-mails, de qualquer data, dos projetos cuja vistoria foi lançada nas últimas
     * {@link #JANELA_DA_BUSCA_RETROATIVA}. A mesma falha da busca do envio, uma etapa adiante: a
     * vistoria da solicitação 2610021352 foi aprovada no portal no começo de outubro, mas só pôde
     * ser lançada aqui em 22/10, depois de o projeto ser aprovado — e a integração nunca cria
     * vistoria, então os avisos ficaram sem destino até saírem da janela de sete dias.
     * <p>
     * ⚠️ Esses e-mails são lidos {@linkplain RetornoCoelbaService#processar(MensagemGmail, boolean)
     * só para a vistoria}: a busca traz o histórico inteiro da solicitação, e um cancelamento
     * antigo reaplicado reprovaria o projeto já aprovado.
     */
    private List<String> buscaRetroativaDaVistoria() {
        List<String> numeros = projetoRepository.numerosComVistoriaLancadaDesde(
                Instant.now().minus(JANELA_DA_BUSCA_RETROATIVA));
        if (numeros.isEmpty()) {
            return List.of();
        }
        log.debug("Gmail: busca sem limite de data para {} projeto(s) com vistoria lançada há pouco",
                numeros.size());
        return gmailClient.listarIdsSemLimiteDeData(numeros);
    }

    /**
     * Uma mensagem por vez, com a falha contida: a leitura de um e-mail problemático não pode
     * impedir os outros da mesma execução de serem aplicados.
     */
    private ResultadoProcessamento processar(String id, boolean somenteVistoria) {
        try {
            return retornoCoelbaService.processar(gmailClient.buscar(id), somenteVistoria);
        } catch (RuntimeException falha) {
            // Falha ao buscar a mensagem no Gmail: o RetornoCoelbaService nem chegou a ser
            // chamado, então não há registro em email_coelba — e é proposital que não haja. A
            // mensagem continua "não processada" e volta na execução seguinte.
            log.error("Falha ao buscar a mensagem {} no Gmail; será tentada de novo", id, falha);
            return ResultadoProcessamento.ERRO;
        }
    }
}
