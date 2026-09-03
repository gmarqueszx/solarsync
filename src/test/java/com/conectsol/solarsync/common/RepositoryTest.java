package com.conectsol.solarsync.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import com.conectsol.solarsync.TestcontainersConfiguration;
import com.conectsol.solarsync.common.config.JpaAuditingConfig;

/**
 * Slice de persistência contra um Postgres real (Testcontainers), não H2: o schema usa
 * {@code CHECK} e {@code GENERATED AS IDENTITY} específicos do Postgres, e testar em H2 daria
 * falsa confiança.
 * <p>
 * O {@code @Import} do {@link JpaAuditingConfig} é obrigatório: o slice do
 * {@code @DataJpaTest} não faz component scan de {@code @Configuration}, então sem ele os
 * callbacks de {@code criadoEm}/{@code atualizadoEm} não rodam e todo insert viola o NOT NULL.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@DataJpaTest
@Import({ TestcontainersConfiguration.class, JpaAuditingConfig.class })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public @interface RepositoryTest {
}
