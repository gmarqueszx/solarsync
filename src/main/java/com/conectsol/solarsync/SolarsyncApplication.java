package com.conectsol.solarsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * {@code @EnableJpaAuditing} fica aqui, e não numa {@code @Configuration} separada, porque
 * o slice do {@code @DataJpaTest} exclui {@code @Component}s do scan — só as anotações da
 * própria classe de configuração principal são processadas. Numa classe separada, os
 * callbacks de criado_em/atualizado_em não rodariam nos testes de repository.
 */
@SpringBootApplication
@EnableJpaAuditing
public class SolarsyncApplication {

	public static void main(String[] args) {
		SpringApplication.run(SolarsyncApplication.class, args);
	}

}
