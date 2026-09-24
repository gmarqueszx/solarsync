package com.conectsol.solarsync.integracao.gmail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
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
        try {
            if (propriedades.somenteProjetosConhecidos()) {
                List<String> numeros = projetoRepository.numerosAguardandoRetornoDaCoelba();
                // Em DEBUG e não em INFO: em regime é a mesma informação a cada quinze minutos.
                // Mas é a primeira coisa a olhar quando "a integração parou de funcionar" —
                // lista vazia significa que nenhum projeto está esperando retorno, e aí não há
                // e-mail a buscar, o que é diferente de a caixa estar vazia.
                log.debug("Gmail: buscando retorno de {} projeto(s) aguardando", numeros.size());
                ids = gmailClient.listarIdsDosProjetos(numeros);
            } else {
                ids = gmailClient.listarIds();
            }
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
            ResultadoProcessamento resultado = processar(id);
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

        log.info("Coelba por e-mail: {} mensagem(ns) nova(s) processada(s) — {}",
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
     * Uma mensagem por vez, com a falha contida: a leitura de um e-mail problemático não pode
     * impedir os outros da mesma execução de serem aplicados.
     */
    private ResultadoProcessamento processar(String id) {
        try {
            return retornoCoelbaService.processar(gmailClient.buscar(id));
        } catch (RuntimeException falha) {
            // Falha ao buscar a mensagem no Gmail: o RetornoCoelbaService nem chegou a ser
            // chamado, então não há registro em email_coelba — e é proposital que não haja. A
            // mensagem continua "não processada" e volta na execução seguinte.
            log.error("Falha ao buscar a mensagem {} no Gmail; será tentada de novo", id, falha);
            return ResultadoProcessamento.ERRO;
        }
    }
}
