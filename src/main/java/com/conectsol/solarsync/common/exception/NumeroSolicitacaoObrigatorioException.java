package com.conectsol.solarsync.common.exception;

/**
 * Tentativa de enviar ou reenviar um projeto à Coelba sem o número da solicitação.
 * <p>
 * Vira <b>409</b> e não 400: o corpo até pode estar sintaticamente correto (a mesma exceção é
 * lançada quando a origem não é a tela — a importação da planilha, por exemplo), e o que está
 * errado é a operação pedida para o estado em que o projeto está. Fica junto das outras guardas
 * de fluxo, como {@code DEBITO_NAO_CONSULTADO}, que também barram o envio.
 * <p>
 * O {@code @NotBlank} do {@code ProjetoEncaminharRequest} pega o caso da tela antes daqui, com
 * 400 e o campo apontado. Esta existe para a guarda valer em qualquer origem.
 */
public class NumeroSolicitacaoObrigatorioException extends RuntimeException {

    public NumeroSolicitacaoObrigatorioException() {
        super("Informe o número da solicitação devolvido pela Coelba: é por ele que o retorno "
                + "por e-mail é casado com este projeto.");
    }
}
