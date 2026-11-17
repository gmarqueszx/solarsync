package com.conectsol.solarsync.common.exception;

/**
 * Tentativa de gravar num projeto um número de solicitação que já está em <b>outro</b> projeto.
 * <p>
 * Vira <b>409</b> {@code NUMERO_SOLICITACAO_DUPLICADO}. A coluna segue sem UNIQUE (a importação
 * da planilha traz o campo irregular, e a válvula {@code corrigir-status} precisa continuar
 * passando), mas pela operação normal dois projetos com o mesmo número são sempre um engano — e
 * um engano caro: o retorno da Coelba por e-mail acha dois candidatos, cai em {@code AMBIGUO} e
 * não aplica em nenhum, em silêncio. Foi o que deixou um projeto aprovado parado em
 * {@code ENCAMINHADO} em 11/11/2026, com o cliente cadastrado duas vezes.
 * <p>
 * A mensagem nomeia o cliente do outro projeto porque a causa quase sempre é cadastro duplicado,
 * e é o nome que leva quem está na tela até ele.
 */
public class NumeroSolicitacaoDuplicadoException extends RuntimeException {

    public NumeroSolicitacaoDuplicadoException(String numeroSolicitacao, Long outroProjetoId,
            String outroCliente) {
        super("O número de solicitação %s já está no projeto %d, do cliente %s. Confira se o "
                .formatted(numeroSolicitacao, outroProjetoId, outroCliente)
                + "cliente não está cadastrado duas vezes: com o mesmo número em dois projetos, "
                + "o retorno da Coelba por e-mail não é aplicado em nenhum.");
    }
}
