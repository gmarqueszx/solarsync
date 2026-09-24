package com.conectsol.solarsync.cliente.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param dataPagamento    marco zero das métricas do dashboard: "tempo médio sem ninguém mexer no
 *                         cliente" é medido a partir daqui
 * @param somentePendencia cliente avulso, mandado ao setor só para resolver uma pendência: o
 *                         fluxo termina quando ela é resolvida, sem nascer projeto. Fica no
 *                         cadastro, e não num endpoint de ação como a triagem, porque não é uma
 *                         etapa — é o desenho do fluxo daquele cliente, e desmarcá-lo é a
 *                         maneira de devolvê-lo ao fluxo completo
 * @param banco            projeto pago por financiamento bancário. Vem marcado da importação do
 *                         Nectar quando a oportunidade estava na etapa de entrada de Banco, e é
 *                         editável aqui para o cadastro feito à mão
 */
public record ClienteRequest(
        @NotBlank @Size(max = 150) String nome,
        @Size(max = 100) String cidade,
        @Size(max = 150) String vendedor,
        LocalDate dataPagamento,
        @Size(max = 30) String ucCoelba,
        @Size(max = 20) String telefone,
        Boolean somentePendencia,
        Boolean banco) {

    /**
     * ⚠️ {@code Boolean} e não {@code boolean}, e o motivo é uma pegadinha do Jackson 3: ele
     * ativa {@code FAIL_ON_NULL_FOR_PRIMITIVES} por padrão, ao contrário do Jackson 2. Com
     * componentes primitivos, <b>todo</b> corpo que omitisse estas duas chaves seria recusado com
     * 400 — inclusive os que já existem no frontend, nos testes e na importação. O envelope
     * aceita a ausência; o valor efetivo é normalizado aqui, num lugar só, para o resto do código
     * continuar lendo um booleano de verdade.
     */
    public ClienteRequest {
        somentePendencia = somentePendencia != null && somentePendencia;
        banco = banco != null && banco;
    }
}
