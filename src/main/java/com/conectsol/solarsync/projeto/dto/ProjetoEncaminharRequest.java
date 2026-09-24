package com.conectsol.solarsync.projeto.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo do envio e do reenvio à Coelba.
 * <p>
 * As datas seguem opcionais: sem {@code dataEncaminhado}, assume hoje. Aceitá-las
 * explicitamente é o que permite registrar envio retroativo na importação da planilha.
 *
 * @param numeroSolicitacao número devolvido pela Coelba ao receber o projeto.
 *                          <b>Obrigatório</b> (decisão do usuário em 17/09/2026): é a chave que
 *                          casa o retorno por e-mail com o projeto (seção 9), e sem ela a
 *                          automação da etapa 3 não tem como saber de que projeto o e-mail fala
 *                          — teria de casar por nome de cliente, que é ambíguo. Era opcional
 *                          porque o retorno da Coelba às vezes demora; na prática isso produzia
 *                          projeto enviado sem chave, que o e-mail nunca alcança
 */
public record ProjetoEncaminharRequest(
        LocalDate dataArt,
        LocalDate dataEncaminhado,
        @NotBlank @Size(max = 50) String numeroSolicitacao) {
}
