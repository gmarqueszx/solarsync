package com.conectsol.solarsync;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Canário da configuração: é este teste que quebra primeiro se a chain de segurança, o JWT ou
 * qualquer bean novo impedirem o contexto de subir.
 * <p>
 * Sobre o {@code dados-de-exemplo=false}, ver a explicação em
 * {@code common.AbstractIntegrationTest}.
 */
@SpringBootTest(properties = "solarsync.dados-de-exemplo=false")
@Import(TestcontainersConfiguration.class)
class SolarsyncApplicationTests {

	@Test
	void contextLoads() {
	}

}
