package com.conectsol.solarsync.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Preenchimento de {@code criadoEm}/{@code atualizadoEm} do {@code BaseEntity}.
 * <p>
 * Fica numa {@code @Configuration} própria, e <b>não</b> na classe principal, porque
 * {@code @EnableJpaAuditing} exige um metamodelo JPA: na classe principal ela quebraria todo
 * {@code @WebMvcTest}, que sobe sem JPA. Em troca, os slices de {@code @DataJpaTest} não a
 * enxergam pelo component scan — por isso a anotação de teste {@code @RepositoryTest} a
 * importa explicitamente. Sem esse import, {@code criado_em} chega nulo e viola o NOT NULL.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
