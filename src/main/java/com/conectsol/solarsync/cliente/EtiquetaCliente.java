package com.conectsol.solarsync.cliente;

import java.util.ArrayList;
import java.util.List;

/**
 * Os selos que a tela mostra ao lado do nome do cliente.
 * <p>
 * <b>Derivadas, nunca armazenadas.</b> É o que atende, por construção, ao "não duplicar
 * etiquetas caso a operação seja executada novamente": uma lista calculada a cada leitura não
 * tem como acumular repetição, enquanto uma tabela de etiquetas dependeria de todo caminho de
 * escrita lembrar de conferir antes de inserir.
 * <p>
 * Ficam no servidor, e não no frontend, pela razão que a seção 6 do CLAUDE.md já registra sobre
 * as listas de referência: a regra de "o que é um projeto Banco" precisa valer também para a
 * importação do Nectar e para qualquer consumidor futuro da API, e duas cópias divergem.
 */
public enum EtiquetaCliente {

    /** O cadastro veio da sincronização com o Nectar, não da tela. */
    CRM,

    /**
     * Projeto pago por financiamento bancário. Sai de {@code cliente.banco}, que a importação do
     * Nectar liga quando a oportunidade veio da etapa de entrada marcada como Banco — e que
     * alguém também pode ligar à mão no cadastro.
     */
    BANCO;

    public static List<EtiquetaCliente> de(Cliente cliente) {
        List<EtiquetaCliente> etiquetas = new ArrayList<>(2);
        if (OrigemCliente.de(cliente) == OrigemCliente.CRM_NECTAR) {
            etiquetas.add(CRM);
        }
        if (cliente.isBanco()) {
            etiquetas.add(BANCO);
        }
        return List.copyOf(etiquetas);
    }
}
