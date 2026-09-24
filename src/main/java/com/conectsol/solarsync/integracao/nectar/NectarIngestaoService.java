package com.conectsol.solarsync.integracao.nectar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.conectsol.solarsync.cliente.ClienteService;
import com.conectsol.solarsync.cliente.dto.ClienteRequest;
import com.conectsol.solarsync.common.referencia.Referencias;

import lombok.RequiredArgsConstructor;

/**
 * Transforma um negócio fechado no Nectar no cliente que entra na etapa 1 do fluxo.
 * <p>
 * <b>Cria só o Cliente</b>, em {@code AGUARDANDO_VERIFICACAO} — e não o Projeto. O CRM não sabe
 * se há pendência na Coelba, e quem sabe é a analista que consulta. Criando o projeto aqui, o
 * cliente sairia da fila da triagem como se alguém já tivesse checado, que é exatamente a
 * confusão entre "checado, não tem nada" e "ninguém olhou ainda" que o {@code StatusTriagem}
 * existe para desfazer. O Projeto nasce sozinho depois, pelos listeners que já existem: a
 * triagem conclui sem pendência, ou a pendência é resolvida.
 * <p>
 * <b>Só cria, nunca atualiza.</b> Reimportar um negócio já conhecido não mexe no cliente: a
 * analista corrige nome, cidade e telefone na triagem, e deixar o CRM sobrescrever isso a cada
 * dez minutos desfaria a correção. Quem manda no cadastro depois da entrada é o SolarSync.
 */
@Service
@RequiredArgsConstructor
public class NectarIngestaoService {

    private static final Logger log = LoggerFactory.getLogger(NectarIngestaoService.class);

    /** Limites das colunas de {@code cliente}; o CRM não tem os mesmos. */
    private static final int MAXIMO_NOME = 150;
    private static final int MAXIMO_CIDADE = 100;
    private static final int MAXIMO_VENDEDOR = 150;
    private static final int MAXIMO_TELEFONE = 20;

    private final ClienteService clienteService;
    private final NectarProperties propriedades;
    private final Referencias referencias;

    /**
     * {@code REQUIRES_NEW} por oportunidade: uma que falhe não pode desfazer as que já entraram
     * na mesma execução do job. Vale principalmente para o tamanho de campo — um nome de 200
     * caracteres no CRM não pode fazer a sincronização inteira parar.
     *
     * @return {@code true} se criou cliente; {@code false} se o negócio já era conhecido ou foi
     *         recusado
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean ingerir(OportunidadeNectar oportunidade) {
        if (oportunidade.id() == null) {
            log.warn("Oportunidade do Nectar sem id, ignorada. Sem id não há como garantir que "
                    + "ela não vai ser importada de novo na próxima execução.");
            return false;
        }

        String nome = oportunidade.nomeDoCliente();
        if (nome == null) {
            // Não cria cliente anônimo: o cadastro é a chave de busca de toda a operação, e uma
            // linha em branco na fila da triagem é pior que a ausência dela. WARN e não DEBUG de
            // propósito — se OportunidadeNectar estiver mapeando o campo errado, é por esta
            // linha de log que se descobre.
            log.warn("Oportunidade {} do Nectar sem nome de cliente utilizável (contato, empresa "
                    + "e nome do negócio vazios). Confira o mapeamento em OportunidadeNectar.",
                    oportunidade.id());
            return false;
        }

        // Cidade e vendedor passam pelo padrão do cadastro: o CRM manda "CACULE",
        // "VITÓRIA DA CONQUISTA" e "Vitória Da Conquista" como cidades diferentes, e
        // "Rodrigo soares"/"Deilson Abrantes" como vendedores fora da lista. O que não casa
        // entra nulo, para a analista escolher na tela — texto livre pela porta dos fundos
        // desfaria o padrão que o cadastro pela tela respeita.
        String cidade = referencias.municipioCanonico(oportunidade.cidadeDoCliente());
        String vendedor = referencias.vendedorCanonico(oportunidade.nomeDoVendedor());
        avisarSeNaoCasou("cidade", oportunidade.cidadeDoCliente(), cidade, oportunidade.id());
        avisarSeNaoCasou("vendedor", oportunidade.nomeDoVendedor(), vendedor, oportunidade.id());

        // A etapa por onde a oportunidade entrou é o que diz se o projeto é de banco, e ela não
        // sobrevive à importação — a oportunidade segue andando no CRM. Por isso vira um dado do
        // cliente aqui, na única hora em que ainda se sabe. É de onde sai a etiqueta "Banco" que
        // acompanha o selo "CRM" na triagem, e é o que faz o projeto aprovado voltar para a etapa
        // de banco do Nectar em vez da normal.
        boolean banco = propriedades.entradaDe(oportunidade)
                .map(NectarProperties.EtapaDeEntrada::banco)
                .orElse(false);

        ClienteRequest requisicao = new ClienteRequest(
                truncar(nome, MAXIMO_NOME),
                truncar(cidade, MAXIMO_CIDADE),
                truncar(vendedor, MAXIMO_VENDEDOR),
                oportunidade.dataDePagamento(propriedades.campoDataPagamento()),
                // A UC da Coelba não vem do CRM: ela é descoberta justamente na checagem da
                // etapa 1, por quem consulta a agência virtual. Nasce nula e é preenchida lá.
                null,
                truncar(oportunidade.telefoneDoCliente(), MAXIMO_TELEFONE),
                // O CRM não sabe distinguir o cliente avulso mandado só para resolver pendência;
                // quem marca isso é o gestor, na triagem.
                false,
                banco);

        try {
            boolean criado = clienteService
                    .criarDoNectar(requisicao, String.valueOf(oportunidade.id()))
                    .isPresent();
            if (criado) {
                log.info("Cliente criado a partir da oportunidade {} do Nectar ({}): {}{}",
                        oportunidade.id(), oportunidade.etapaNome(), requisicao.nome(),
                        banco ? " [Banco]" : "");
            }
            return criado;
        } catch (DataIntegrityViolationException corrida) {
            // O índice único da V13 é a garantia de verdade da idempotência. Chega aqui se duas
            // execuções se cruzarem (o agendamento não sobrepõe, mas uma execução manual pode
            // cruzar com a agendada). Duplicata é o resultado esperado, não erro.
            log.debug("Oportunidade {} já importada por outra execução", oportunidade.id());
            return false;
        }
    }

    /**
     * O CRM tinha um valor e ele não está na lista do cadastro. Merece log: é assim que se
     * descobre um vendedor novo que ninguém acrescentou à lista (a importação encontrou
     * "Zenildo", que não estava) ou uma cidade digitada errada no CRM.
     */
    private static void avisarSeNaoCasou(String campo, String bruto, String canonico, Long id) {
        if (bruto != null && !bruto.isBlank() && canonico == null) {
            log.warn("Oportunidade {}: {} '{}' não está na lista do cadastro; o cliente entra "
                    + "sem {} para ser escolhido na triagem.", id, campo, bruto, campo);
        }
    }

    private static String truncar(String valor, int maximo) {
        if (valor == null) {
            return null;
        }
        String limpo = valor.trim();
        return limpo.length() <= maximo ? limpo : limpo.substring(0, maximo);
    }
}
