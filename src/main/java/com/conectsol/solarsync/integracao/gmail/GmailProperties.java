package com.conectsol.solarsync.integracao.gmail;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credenciais e consulta da leitura da caixa de e-mail. Nada tem valor no repositório: as três
 * credenciais vêm do ambiente, como o segredo do JWT (item 6 do checklist).
 * <p>
 * Autenticação por <b>refresh token</b> de uma conta OAuth comum, e não por conta de serviço com
 * delegação de domínio: a delegação exige configuração no console do Google Workspace pelo
 * administrador do domínio, e aqui basta uma autorização única da caixa que já recebe o e-mail
 * da Coelba. O escopo necessário é só {@code gmail.readonly} — o job lê e nunca escreve na caixa
 * (é por isso que a marca de "já processei" fica na tabela {@code email_coelba}, e não num
 * rótulo no Gmail).
 *
 * @param ativo             desligado por padrão. Ligado, cria o job agendado e o cliente HTTP
 * @param somenteConferencia ensaio antes da estreia: o job lê os e-mails de verdade, roda o
 *                          parser e grava o que entendeu em {@code email_coelba}, mas
 *                          <b>não muda status de projeto nenhum</b>. Existe porque a redação
 *                          real do e-mail da Coelba nunca foi vista — as listas de termos de
 *                          {@code solarsync.coelba} são o provável, não o observado — e um
 *                          e-mail mal interpretado reprovaria um projeto de verdade,
 *                          sujando o {@code historico_status} que sustenta o dashboard.
 *                          Enquanto está ligado, cada execução relê as mesmas mensagens e
 *                          <b>atualiza</b> o registro: é o que permite ajustar os termos e
 *                          conferir de novo sem esperar e-mail novo
 * @param somenteProjetosConhecidos em produção, buscar apenas os e-mails que citam o número de
 *                          solicitação de um projeto que ainda espera retorno (pedido do usuário
 *                          em 19/09/2026). São ~30 e-mails do portal por dia e só os desses
 *                          projetos podem virar mudança de status; os demais só produziam linha
 *                          de ruído em {@code email_coelba}, justamente onde se vai procurar por
 *                          que um projeto mudou de status.
 *                          <p>
 *                          ⚠️ <b>Desligar durante o ensaio.</b> Num banco sem os projetos da
 *                          operação — o de desenvolvimento — a lista de números sai vazia, o job
 *                          não busca nada e não há o que conferir. É também o que permite
 *                          descobrir formato novo varrendo a caixa inteira
 * @param numerosPorConsulta quantos números entram em cada consulta ao Gmail. A busca sai em
 *                          lotes porque a lista pode ter centenas de projetos e a consulta tem
 *                          limite de tamanho. Cada lote é uma listagem a mais — não uma busca de
 *                          mensagem a mais, que é o que custa caro
 * @param clientId          id do cliente OAuth criado no Google Cloud Console
 * @param clientSecret      segredo do mesmo cliente OAuth
 * @param refreshToken      obtido uma única vez no consentimento, com {@code access_type=offline}
 * @param usuario           caixa a ler. {@code me} é a própria conta que autorizou
 * @param consulta          filtro no formato de busca do Gmail. O recorte por remetente é o que
 *                          impede o parser de sequer olhar e-mail que não é da Coelba, e o
 *                          {@code newer_than} é o que mantém a lista curta — releitura é barata
 *                          porque {@code email_coelba} já sabe o que foi processado
 * @param maximoPorExecucao teto de mensagens por execução; cada uma é uma chamada HTTP a mais
 * @param intervalo         de quanto em quanto tempo ler. O e-mail da Coelba é diário, então
 *                          quinze minutos já é folgado
 */
@ConfigurationProperties("solarsync.gmail")
public record GmailProperties(
        boolean ativo,
        boolean somenteConferencia,
        boolean somenteProjetosConhecidos,
        int numerosPorConsulta,
        String clientId,
        String clientSecret,
        String refreshToken,
        String usuario,
        String consulta,
        int maximoPorExecucao,
        Duration intervalo) {

    public GmailProperties {
        usuario = usuario == null || usuario.isBlank() ? "me" : usuario;
        // ⚠️ O padrão anterior era from:(coelba.com.br OR neoenergia.com.br), e não casaria com
        // nenhum e-mail real: o Portal da Geração Distribuída manda de
        // noreplyportalgd@neoenergia.com — domínio .com, sem o .br. A integração ligada teria
        // lido uma caixa vazia todo dia, sem erro em lugar nenhum.
        //
        // Restringido ao remetente exato depois do ensaio de 19/09/2026: a versão ampla trazia
        // junto as faturas mensais ("Mini e Microgeração - Demonstrativo do Faturamento") e as
        // respostas de atendimento, que foram a maioria das mensagens lidas e só produziram
        // NAO_RECONHECIDO — ruído em email_coelba justamente onde se vai procurar por que um
        // projeto mudou de status.
        consulta = consulta == null || consulta.isBlank()
                ? "from:noreplyportalgd@neoenergia.com newer_than:7d"
                : consulta;
        // ⚠️ 50 era o padrão e é pouco: são ~30 e-mails do portal por dia (medido em 19/09/2026 —
        // 5347 em 180 dias), então a janela de 7 dias traz ~210. Em regime só as mensagens novas
        // são buscadas, mas o teto se aplica à LISTAGEM: com 50, qualquer parada de mais de dois
        // dias faria as mais antigas caírem para fora da lista e nunca serem processadas.
        numerosPorConsulta = numerosPorConsulta <= 0 ? 50 : numerosPorConsulta;
        maximoPorExecucao = maximoPorExecucao <= 0 ? 300 : maximoPorExecucao;
        intervalo = intervalo == null ? Duration.ofMinutes(15) : intervalo;
    }

    boolean credenciaisCompletas() {
        return preenchido(clientId) && preenchido(clientSecret) && preenchido(refreshToken);
    }

    private static boolean preenchido(String valor) {
        return valor != null && !valor.isBlank();
    }
}
