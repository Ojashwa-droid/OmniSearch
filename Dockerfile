FROM maven:3.10.0-eclipse-temurin-21 AS build

WORKDIR /workspace
ENV MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=192m"

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package \
    && cp target/*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app app

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 3 http://127.0.0.1:8080/api/omnisearch/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
