# Imagem da API do SolarSync.
#
# Duas etapas: a primeira compila com Maven e o JDK, a segunda carrega só o JRE e o jar. A
# imagem final não tem compilador, Maven nem código-fonte — é o que a torna pequena e reduz a
# superfície de ataque no VPS (item 10 do checklist da seção 8 do CLAUDE.md).
#
# Usa a imagem oficial do Maven em vez do ./mvnw de propósito: o wrapper baixaria a distribuição
# do Maven a cada build sem cache, e o `distributionType=only-script` do wrapper depende de curl
# ou wget existirem na imagem base — dependência a mais para um passo que a imagem do Maven já
# resolve.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# O pom vem sozinho primeiro para que o download das dependências vire uma camada própria: mexer
# em código-fonte não refaz o download. O cache do BuildKit em /root/.m2 cobre o resto.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline

COPY src/ src/
# Sem testes aqui de propósito: a suíte exige Docker (Testcontainers) e rodá-la dentro do build
# significaria Docker dentro de Docker. Os testes rodam antes do deploy, na máquina de quem
# publica — o script deploy/deploy.sh lembra disso.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package


FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Não roda como root: se a aplicação for comprometida, o processo não é dono do container.
RUN addgroup -S solarsync && adduser -S -G solarsync solarsync

COPY --from=build /build/target/solarsync-*.jar app.jar
RUN chown solarsync:solarsync app.jar

USER solarsync
EXPOSE 8080

# MaxRAMPercentage porque o padrão da JVM em container (25%) desperdiça a maior parte da memória
# do VPS. UTC porque é como os timestamps são gravados (hibernate.jdbc.time_zone=UTC); a
# conversão para America/Bahia é feita onde importa, na leitura do e-mail da Coelba.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=UTC"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
