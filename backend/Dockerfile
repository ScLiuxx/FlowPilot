# Build stage: resolve the dependencies once, so a code change does not re-download them.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# Runtime stage: only the JRE and the packaged jar.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system flowpilot \
 && useradd --system --gid flowpilot --create-home flowpilot \
 && apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/target/flowpilot-backend.jar app.jar

USER flowpilot
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# The API and the worker run the same jar. They differ in the Spring profile, which decides
# how much node execution concurrency the instance takes on.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
