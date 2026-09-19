package com.conectsol.solarsync.common.referencia;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.conectsol.solarsync.common.security.PodeLer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * As listas fechadas que o formulário de cadastro de cliente usa. Existe para o frontend deixar
 * de manter a própria cópia (`src/data/constantes.ts`): elas passaram a viver no servidor porque
 * a importação do Nectar precisa normalizar cidade e vendedor contra o mesmo padrão, e duas
 * cópias divergem — o CLAUDE.md (seção 6) já registra o estrago de regra duplicada entre os dois
 * repositórios.
 */
@RestController
@RequestMapping("/api/referencias")
@RequiredArgsConstructor
@Tag(name = "Referências", description = "Listas fechadas do cadastro (municípios, vendedores)")
public class ReferenciaController {

    private final Referencias referencias;

    public record ReferenciasResponse(List<String> municipios, List<String> vendedores) {
    }

    @GetMapping
    @PodeLer
    @Operation(
            summary = "Municípios da Bahia e vendedores, em ordem alfabética",
            description = "É o mesmo padrão que a importação do Nectar aplica: cidade ou "
                    + "vendedor que não casa com estas listas entra nulo, para ser escolhido "
                    + "aqui, em vez de texto livre.")
    public ReferenciasResponse listar() {
        return new ReferenciasResponse(referencias.municipios(), referencias.vendedores());
    }
}
