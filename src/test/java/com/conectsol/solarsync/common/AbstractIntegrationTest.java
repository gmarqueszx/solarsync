package com.conectsol.solarsync.common;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.conectsol.solarsync.TestcontainersConfiguration;

/**
 * Base dos testes de integração.
 * <p>
 * O {@code solarsync.dados-de-exemplo=false} é obrigatório: o Spring Boot lê
 * {@code ./config/application.properties} (a configuração local de desenvolvimento) <b>também
 * durante os testes</b>, e com precedência maior que o classpath — então sem este override a
 * semeadura de exemplo entraria no banco do Testcontainers e quebraria todo teste que confere
 * contagem. Qualquer {@code @SpringBootTest} novo precisa do mesmo cuidado.
 */
@SpringBootTest(properties = "solarsync.dados-de-exemplo=false")
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {
}
