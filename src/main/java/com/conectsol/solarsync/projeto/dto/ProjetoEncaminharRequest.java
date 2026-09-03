package com.conectsol.solarsync.projeto.dto;

import java.time.LocalDate;

/**
 * Ambas as datas são opcionais: ausentes, {@code dataEncaminhado} assume hoje. Aceitá-las
 * explicitamente é o que permite registrar envio retroativo na importação da planilha.
 */
public record ProjetoEncaminharRequest(LocalDate dataArt, LocalDate dataEncaminhado) {
}
