package com.conectsol.solarsync.cliente;

/**
 * De onde o cadastro do cliente veio. Pedido do usuário em 17/09/2026: a triagem precisa
 * distinguir o cliente que a sincronização com o Nectar trouxe do que alguém digitou.
 * <p>
 * Importa porque a confiança nos dados é diferente. O cliente do CRM chega com cidade e vendedor
 * normalizados contra as listas do cadastro, e o que não casou chega <b>nulo</b> — então campo
 * vazio num cliente do CRM significa "o CRM não sabia" e pede o preenchimento de alguém, que é
 * justamente o trabalho da triagem. Num cadastro manual, campo vazio é esquecimento.
 * <p>
 * <b>Derivada, não armazenada</b>: é {@code nectar_oportunidade_id != null}. Uma coluna própria
 * poderia divergir do id que a origina; derivar não pode.
 */
public enum OrigemCliente {

    /** Digitado por alguém em {@code POST /api/clientes}. */
    MANUAL,

    /** Criado pela sincronização com o Nectar (seção 9 do CLAUDE.md). */
    CRM_NECTAR;

    public static OrigemCliente de(Cliente cliente) {
        return cliente.getNectarOportunidadeId() == null ? MANUAL : CRM_NECTAR;
    }
}
