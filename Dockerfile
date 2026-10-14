FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:resolve
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends libreoffice-writer libreoffice-impress fonts-dejavu fonts-liberation fonts-crosextra-carlito fonts-crosextra-caladea curl && rm -rf /var/lib/apt/lists/* && useradd --uid 10001 --create-home converter && mkdir -p /data && chown converter:converter /data
WORKDIR /app
COPY --from=build /build/target/converter.jar /app/converter.jar
USER converter
ENV DATA_DIR=/data
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s CMD curl -fsS http://localhost:${PORT:-8080}/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/converter.jar"]
