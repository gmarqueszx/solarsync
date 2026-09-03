package com.conectsol.solarsync.common.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Envelope de paginação próprio. Existe para não serializar {@code PageImpl} do Spring Data
 * direto no contrato: o JSON dele é instável entre versões (o próprio Boot emite warning) e
 * gera um schema OpenAPI ruim para o frontend consumir.
 */
public record PaginaResponse<T>(
        List<T> conteudo,
        int pagina,
        int tamanho,
        long totalElementos,
        int totalPaginas,
        boolean ultima) {

    public static <E, T> PaginaResponse<T> de(Page<E> pagina, Function<E, T> mapeador) {
        return new PaginaResponse<>(
                pagina.getContent().stream().map(mapeador).toList(),
                pagina.getNumber(),
                pagina.getSize(),
                pagina.getTotalElements(),
                pagina.getTotalPages(),
                pagina.isLast());
    }
}
