package com.conectsol.solarsync.common.exception;

/**
 * Vistoria é solicitada pós-instalação (etapa 4 do fluxo). Sem a data de instalação registrada
 * não há de onde contar o "tempo médio para solicitar vistoria pós-instalação", e uma vistoria
 * pedida antes da usina existir seria reprovada pela Coelba de qualquer forma.
 */
public class ProjetoSemInstalacaoException extends RuntimeException {

    public ProjetoSemInstalacaoException(Long projetoId) {
        super(("Projeto %d não tem data de instalação registrada; registre em "
                + "POST /api/projetos/%d/registrar-instalacao antes de solicitar a vistoria")
                        .formatted(projetoId, projetoId));
    }
}
