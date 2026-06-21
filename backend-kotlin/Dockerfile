FROM eclipse-temurin:26-jdk AS build

WORKDIR /workspace
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx768m -Dorg.gradle.workers.max=1"
COPY gradle gradle
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src src
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:26-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --create-home app

WORKDIR /app
COPY --from=build /workspace/build/libs/ods-sso-backend-2.0.0.jar app.jar

USER app
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD ["curl", "--fail", "http://127.0.0.1:8000/health"]

ENTRYPOINT ["java", "-Xms128m", "-Xmx512m", "-XX:+UseZGC", "-jar", "/app/app.jar"]
