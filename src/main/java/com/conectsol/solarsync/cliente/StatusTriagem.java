package com.conectsol.solarsync.cliente;

/**
 * Resultado da checagem de pendência na Coelba (etapa 1 do fluxo). Existe porque "o cliente não
 * tem pendência" precisava ser um fato <b>registrado</b>, e não a ausência de registro: sem
 * isto, "a Nycole checou e não achou nada" e "ninguém olhou esse cliente ainda" eram
 * indistinguíveis, e o segundo caso é justamente o cliente que se perde.
 * <p>
 * Não há {@code podeIrPara} aqui, ao contrário de {@code StatusPendencia} e
 * {@code StatusProjeto}. As três transições possíveis são todas legítimas na operação real —
 * uma pendência pode aparecer depois de o cliente ter sido liberado, pode ter sido engano, e um
 * novo ciclo (ampliação) pede recheca. Não sobra nenhuma transição absurda para barrar, e uma
 * máquina de estados que aceita tudo é burocracia sem proteção.
 */
public enum StatusTriagem {

    /** Ninguém consultou a Coelba para este cliente ainda. É a fila de trabalho da triagem. */
    AGUARDANDO_VERIFICACAO,

    /** Checado: há pendência. Marcado automaticamente quando uma Pendencia é criada. */
    COM_PENDENCIA,

    /** Checado: nada pendente na Coelba. Libera o cliente para a consulta de débito. */
    SEM_PENDENCIA
}
