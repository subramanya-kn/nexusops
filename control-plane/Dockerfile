# Multi-stage: build the Spring Boot fat jar, then run on a slim JRE as non-root.
# NOTE: bases pinned by tag; resolve to @sha256 digests in CI (see BUILD-LOG deviation).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Cache dependencies first.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy AS runtime
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/control-plane.jar app.jar

# non-root
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin appuser
USER 10001

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=12 \
  CMD curl -fsS http://localhost:8080/actuator/health | grep -q UP

ENTRYPOINT ["java", "-jar", "app.jar"]
