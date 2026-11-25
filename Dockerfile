# Stage 1: build the jar with the Maven wrapper (JDK image, not shipped).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# Dependencies first: this layer is cached until pom.xml changes, so code-only changes rebuild fast.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# Tests need Docker (Testcontainers), so they run in CI with ./mvnw verify, not inside this build.
RUN ./mvnw -B -q package -DskipTests

# Stage 2: runtime. JRE only, no Maven, no source code, no compiler.
FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/target/contra-api-*.jar app.jar
# Non-root: if the app is compromised, the attacker isn't root inside the container.
USER app
EXPOSE 8080
# The JVM sizes its heap from the container's memory limit; 75% instead of the default 25%.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
