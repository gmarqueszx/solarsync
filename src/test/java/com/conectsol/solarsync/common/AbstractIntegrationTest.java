package com.conectsol.solarsync.common;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.conectsol.solarsync.TestcontainersConfiguration;

/**
 * Base dos testes de integração.
 * <p>
 * Os quatro overrides existem pelo mesmo motivo: o Spring Boot lê
 * {@code ./config/application.properties} (a configuração local de desenvolvimento) <b>também
 * durante os testes</b>, e com precedência maior que o classpath. Então todo valor de
 * conveniência que alguém põe lá vaza para dentro do Testcontainers. Qualquer
 * {@code @SpringBootTest} novo precisa do mesmo cuidado.
 * <ul>
 *   <li>{@code dados-de-exemplo=false} — sem ele a semeadura de exemplo entra no banco do
 *       Testcontainers e quebra todo teste que confere contagem (já quebrou 14);</li>
 *   <li>{@code nectar.ativo=false} e {@code gmail.ativo=false} — sem eles, ligar uma integração
 *       na configuração local criaria os jobs agendados dentro de <b>toda</b> suíte de teste, e
 *       elas fariam chamada de rede de verdade: o job do Nectar varrendo o CRM da empresa e o do
 *       Gmail lendo a caixa real, aplicando status em projetos do banco de teste. Mais barato
 *       travar aqui que descobrir pelo tráfego.</li>
 *   <li>{@code gmail.somente-conferencia=false} — este custou quatro testes vermelhos para
 *       aparecer. O modo conferência ficou ligado na configuração local (é o estado normal
 *       dela enquanto a redação do e-mail da Coelba não for conferida) e vazou para a suíte:
 *       todo teste que prova que o e-mail <b>muda</b> o status do projeto passou a receber
 *       {@code CONFERENCIA}. Note que {@code gmail.ativo=false} não protege disto — o
 *       {@code RetornoCoelbaService} não é condicional, só o job e o cliente HTTP são.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "solarsync.dados-de-exemplo=false",
        "solarsync.nectar.ativo=false",
        "solarsync.gmail.ativo=false",
        "solarsync.gmail.somente-conferencia=false" })
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {
}
