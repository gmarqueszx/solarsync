package com.conectsol.solarsync.integracao.gmail;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.conectsol.solarsync.common.FusoDaOperacao;
import com.conectsol.solarsync.common.exception.TransicaoStatusInvalidaException;
import com.conectsol.solarsync.integracao.UsuarioIntegracao;
import com.conectsol.solarsync.projeto.Projeto;
import com.conectsol.solarsync.projeto.ProjetoRepository;
import com.conectsol.solarsync.projeto.ProjetoService;
import com.conectsol.solarsync.projeto.StatusProjeto;
import com.conectsol.solarsync.vistoria.StatusVistoria;
import com.conectsol.solarsync.vistoria.Vistoria;
import com.conectsol.solarsync.vistoria.VistoriaRepository;
import com.conectsol.solarsync.vistoria.VistoriaService;

import lombok.RequiredArgsConstructor;

/**
 * Casa o e-mail lido com o projeto e aplica o resultado. É a etapa 3 do fluxo automatizada: "o
 * status é acompanhado por e-mail diário (aprovado / reprovado / em análise)".
 * <p>
 * Passa por {@link ProjetoService}, o mesmo caminho da tela — é o que faz a auditoria em
 * {@code historico_status} e a máquina de estados valerem igual, independente de a mudança vir
 * de um clique ou de um e-mail (regra arquitetural da seção 3).
 * <p>
 * <b>Deliberadamente não é {@code @Transactional}.</b> Cada passo abre a sua transação: a
 * mudança do projeto dentro do {@code ProjetoService}, e o registro em {@code email_coelba}
 * depois. Se fossem a mesma, uma transição barrada pela máquina de estados marcaria a transação
 * como "somente rollback" e o registro do que aconteceu — justamente o que se quer guardar —
 * seria perdido junto.
 */
@Service
@RequiredArgsConstructor
public class RetornoCoelbaService {

    private static final Logger log = LoggerFactory.getLogger(RetornoCoelbaService.class);

    /** Os dois status em que o projeto está esperando resposta da Coelba. */
    private static final List<StatusProjeto> AGUARDANDO_RETORNO =
            List.of(StatusProjeto.ENCAMINHADO, StatusProjeto.REENCAMINHADO);

    private final ParserEmailCoelba parser;
    private final ProjetoRepository projetoRepository;
    private final ProjetoService projetoService;
    private final EmailCoelbaRepository emailCoelbaRepository;
    private final UsuarioIntegracao usuarioIntegracao;
    private final GmailProperties gmailProperties;
    private final VistoriaRepository vistoriaRepository;
    private final VistoriaService vistoriaService;

    /**
     * Lê, aplica e registra — sempre registra, inclusive quando não aplicou nada. O e-mail que a
     * integração não entendeu é o caso que não pode desaparecer em silêncio.
     */
    public ResultadoProcessamento processar(MensagemGmail mensagem) {
        Registro registro;
        try {
            registro = aplicar(mensagem);
        } catch (RuntimeException falha) {
            log.error("Falha ao processar o e-mail {} da Coelba", mensagem.id(), falha);
            registro = new Registro(ResultadoProcessamento.ERRO, null, null,
                    falha.getClass().getSimpleName() + ": " + falha.getMessage());
        }

        gravar(mensagem, registro);
        return registro.resultado();
    }

    /** O que aconteceu com um e-mail, antes de virar linha em {@code email_coelba}. */
    private record Registro(ResultadoProcessamento resultado, Long projetoId, String numero,
            String detalhe) {
    }

    private Registro aplicar(MensagemGmail mensagem) {
        RetornoCoelba retorno = parser.ler(mensagem.texto());

        if (retorno.ambiguo()) {
            return new Registro(ResultadoProcessamento.AMBIGUO, null, null,
                    "O e-mail afirma aprovação e reprovação ao mesmo tempo. Ajuste as listas "
                            + "de termos em solarsync.coelba se a redação da Coelba mudou.");
        }
        if (retorno.resultado() == null) {
            return new Registro(ResultadoProcessamento.NAO_RECONHECIDO, null, null,
                    "Nenhum termo de aprovação, reprovação ou análise reconhecido no texto.");
        }
        if (retorno.numeros().isEmpty()) {
            return new Registro(ResultadoProcessamento.SEM_CORRESPONDENCIA, null, null,
                    "Resultado " + retorno.resultado() + " reconhecido, mas nenhum número no "
                            + "texto para casar com um projeto.");
        }

        List<Projeto> candidatos = projetoRepository.findByNumeroSolicitacaoIn(retorno.numeros());
        if (candidatos.isEmpty()) {
            // O resultado lido entra no registro, e o primeiro número candidato vai para a
            // coluna própria. Sem os dois, "sem correspondência" não distingue "o parser leu
            // uma aprovação de um projeto que não está aqui" de "o parser leu qualquer coisa" —
            // e era essa a pergunta no ensaio de 19/09/2026, em que 113 e-mails reais caíram
            // aqui por o banco de desenvolvimento não ter os projetos da operação.
            return new Registro(ResultadoProcessamento.SEM_CORRESPONDENCIA, null,
                    retorno.numeros().get(0),
                    "Lido como " + retorno.resultado() + ", mas nenhum projeto tem os números "
                            + retorno.numeros() + ".");
        }

        // Entre projetos com o mesmo número, os que estão esperando resposta da Coelba têm
        // precedência: é o reenvio que produz número repetido, e o retorno é sobre o que está
        // pendente, não sobre o ciclo já encerrado.
        List<Projeto> aguardando = candidatos.stream()
                .filter(projeto -> AGUARDANDO_RETORNO.contains(projeto.getStatus()))
                .toList();
        List<Projeto> alvos = aguardando.isEmpty() ? candidatos : aguardando;

        if (alvos.size() > 1) {
            return new Registro(ResultadoProcessamento.AMBIGUO, null, null,
                    "Mais de um projeto candidato (" + alvos.stream().map(Projeto::getId).toList()
                            + ") para os números " + retorno.numeros() + ". Nada aplicado.");
        }

        Projeto alvo = alvos.get(0);
        return aplicarNoProjeto(alvo, retorno, mensagem.recebidoEm());
    }

    private Registro aplicarNoProjeto(Projeto projeto, RetornoCoelba retorno, Instant recebidoEm) {
        String numero = projeto.getNumeroSolicitacao();
        Long id = projeto.getId();
        // A entidade veio de uma consulta fora de transação, então está destacada e continua
        // mostrando o status anterior mesmo depois de o service mudá-lo — é o que se quer para
        // a mensagem "de X para Y". Guardado em variável para não ficar dependendo disso.
        StatusProjeto statusAnterior = projeto.getStatus();

        if (retorno.resultado() == ResultadoCoelba.EM_ANALISE) {
            return new Registro(ResultadoProcessamento.SEM_ALTERACAO, id, numero,
                    "Acompanhamento sem decisão; projeto segue em " + statusAnterior + ".");
        }

        if (retorno.resultado() == ResultadoCoelba.VISTORIA_SOLICITADA
                || retorno.resultado() == ResultadoCoelba.VISTORIA_APROVADA) {
            return naVistoria(projeto, retorno.resultado(), recebidoEm);
        }

        StatusProjeto destino = retorno.resultado() == ResultadoCoelba.APROVADO
                ? StatusProjeto.APROVADO
                : StatusProjeto.REPROVADO;

        // Releitura de um e-mail já refletido: nem chama o service. A transição para o status
        // atual é no-op lá, mas reprovar de novo sobrescreveria o motivo que alguém pode ter
        // corrigido à mão.
        if (statusAnterior == destino) {
            return new Registro(ResultadoProcessamento.SEM_ALTERACAO, id, numero,
                    "Projeto já estava em " + destino + ".");
        }

        // O ensaio: leu, casou com um projeto e sabe o que faria — e para aqui. Só este ponto
        // desvia, e de propósito: tudo que vem antes (parser, extração do número, escolha do
        // projeto, motivo da reprova) é exatamente o que roda no modo normal, senão o ensaio
        // provaria um caminho diferente do que vai ao ar.
        if (gmailProperties.somenteConferencia()) {
            String teria = "Modo conferência: teria mudado de " + statusAnterior + " para "
                    + destino + ". Nada foi aplicado.";
            return new Registro(ResultadoProcessamento.CONFERENCIA, id, numero,
                    destino == StatusProjeto.REPROVADO && retorno.motivo() != null
                            ? teria + " Motivo lido: " + retorno.motivo()
                            : teria);
        }

        Long autor = usuarioIntegracao.id();
        try {
            if (destino == StatusProjeto.APROVADO) {
                projetoService.aprovar(id, dataDo(recebidoEm), autor);
            } else {
                projetoService.reprovar(id, retorno.motivo(), autor);
            }
        } catch (TransicaoStatusInvalidaException barrada) {
            // A guarda fez o trabalho dela: aprovar um projeto que nunca foi encaminhado
            // gravaria data de aprovação com data de envio nula, e a métrica de tempo até
            // aprovação sairia absurda.
            return new Registro(ResultadoProcessamento.TRANSICAO_INVALIDA, id, numero,
                    barrada.getMessage());
        }

        log.info("Coelba por e-mail: projeto {} (solicitação {}) de {} para {}",
                id, numero, statusAnterior, destino);

        return new Registro(ResultadoProcessamento.APLICADO, id, numero,
                "Status alterado para " + destino + " pela leitura do e-mail.");
    }

    /**
     * Etapa 4 automatizada: o portal notifica a vistoria, e o retorno avança a vistoria que já
     * existe.
     * <p>
     * ⚠️ <b>Nunca cria vistoria</b> (decisão do usuário em 19/09/2026). Criar exigiria a data de
     * instalação do projeto, que é evento de campo — ninguém além da equipe sabe quando a usina
     * foi instalada, e o portal não tem esse dado. As alternativas seriam furar a guarda
     * {@code PROJETO_SEM_INSTALACAO} ou inventar a data a partir do e-mail, e a segunda faria a
     * métrica "tempo para solicitar vistoria pós-instalação" medir o nada.
     * <p>
     * O preço é assumido e está registrado: se ninguém lançou a instalação, o retorno da Coelba
     * cai como {@code SEM_CORRESPONDENCIA} — visível em {@code email_coelba}, e não perdido.
     */
    private Registro naVistoria(Projeto projeto, ResultadoCoelba resultado, Instant recebidoEm) {
        Long projetoId = projeto.getId();
        String numero = projeto.getNumeroSolicitacao();

        List<Vistoria> vistorias = vistoriaRepository.findByProjetoId(projetoId);
        if (vistorias.isEmpty()) {
            return new Registro(ResultadoProcessamento.SEM_CORRESPONDENCIA, projetoId, numero,
                    "Portal informa " + resultado + ", mas o projeto não tem vistoria lançada. "
                            + "Registre a instalação e solicite a vistoria na tela; a automação "
                            + "não cria vistoria porque a data de instalação é evento de campo.");
        }
        if (vistorias.size() > 1) {
            return new Registro(ResultadoProcessamento.AMBIGUO, projetoId, numero,
                    "Projeto com mais de uma vistoria " + vistorias.stream()
                            .map(Vistoria::getId).toList() + "; nada aplicado.");
        }

        Vistoria vistoria = vistorias.get(0);
        StatusVistoria statusAnterior = vistoria.getStatus();
        StatusVistoria destino = resultado == ResultadoCoelba.VISTORIA_APROVADA
                ? StatusVistoria.APROVADA
                : StatusVistoria.SOLICITADA;

        if (statusAnterior == destino) {
            return new Registro(ResultadoProcessamento.SEM_ALTERACAO, projetoId, numero,
                    "Vistoria " + vistoria.getId() + " já estava em " + destino + ".");
        }

        if (gmailProperties.somenteConferencia()) {
            return new Registro(ResultadoProcessamento.CONFERENCIA, projetoId, numero,
                    "Modo conferência: teria mudado a vistoria " + vistoria.getId() + " de "
                            + statusAnterior + " para " + destino + ". Nada foi aplicado.");
        }

        Long autor = usuarioIntegracao.id();
        try {
            if (destino == StatusVistoria.APROVADA) {
                vistoriaService.aprovar(vistoria.getId(), dataDo(recebidoEm), autor);
            } else {
                // Só a reprovada volta para solicitada; a aprovada não retrocede. É a máquina de
                // estados que barra, e é ela que impede o e-mail atrasado do portal — que chega
                // fora de ordem — de desfazer uma vistoria já aprovada.
                vistoriaService.resolicitar(vistoria.getId(), dataDo(recebidoEm), autor);
            }
        } catch (TransicaoStatusInvalidaException barrada) {
            return new Registro(ResultadoProcessamento.TRANSICAO_INVALIDA, projetoId, numero,
                    barrada.getMessage());
        }

        log.info("Coelba por e-mail: vistoria {} do projeto {} de {} para {}",
                vistoria.getId(), projetoId, statusAnterior, destino);

        return new Registro(ResultadoProcessamento.APLICADO, projetoId, numero,
                "Vistoria " + vistoria.getId() + " alterada para " + destino
                        + " pela leitura do e-mail.");
    }

    /** Nula quando o Gmail não informou a data; o {@code ProjetoService} então usa hoje. */
    private static LocalDate dataDo(Instant recebidoEm) {
        return recebidoEm == null ? null : recebidoEm.atZone(FusoDaOperacao.ZONA).toLocalDate();
    }

    private void gravar(MensagemGmail mensagem, Registro registro) {
        // No modo conferência o job relê as mesmas mensagens a cada execução, então o registro é
        // atualizado em vez de duplicado. É isso que fecha o ciclo de ajustar os termos em
        // solarsync.coelba, reiniciar e ver o novo veredito sobre os mesmos e-mails — sem isso o
        // único em mensagem_id barraria a segunda gravação e a tabela guardaria só o primeiro
        // palpite, que é justamente o que se está tentando corrigir.
        //
        // Fora dele, só o registro provisório é atualizado — o e-mail que esperava o projeto
        // existir. Um registro definitivo fica como está: reler um APLICADO devolveria
        // SEM_ALTERACAO, e sobrescrevê-lo apagaria da trilha que foi este e-mail que mudou o
        // projeto.
        EmailCoelba existente = emailCoelbaRepository.findByMensagemId(mensagem.id())
                .orElse(null);
        if (existente != null) {
            if (gmailProperties.somenteConferencia() || existente.aguardaProjeto()) {
                preencher(existente, mensagem, registro);
                emailCoelbaRepository.save(existente);
            }
            return;
        }

        try {
            EmailCoelba novo = new EmailCoelba();
            preencher(novo, mensagem, registro);
            emailCoelbaRepository.save(novo);
        } catch (DataIntegrityViolationException jaGravado) {
            // Outra execução gravou a mesma mensagem no meio do caminho. O único em mensagem_id
            // é a garantia de verdade da idempotência; chegar aqui significa que ela funcionou.
            log.debug("E-mail {} já registrado por outra execução", mensagem.id());
        }
    }

    private static void preencher(EmailCoelba linha, MensagemGmail mensagem, Registro registro) {
        linha.setMensagemId(mensagem.id());
        linha.setRecebidoEm(mensagem.recebidoEm());
        linha.setAssunto(truncar(mensagem.assunto(), 500));
        linha.setNumeroSolicitacao(truncar(registro.numero(), 50));
        linha.setResultado(registro.resultado());
        linha.setProjetoId(registro.projetoId());
        linha.setDetalhe(truncar(registro.detalhe(), 1000));
    }

    private static String truncar(String valor, int maximo) {
        if (valor == null) {
            return null;
        }
        return valor.length() <= maximo ? valor : valor.substring(0, maximo);
    }
}
