# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:26-jdk AS build

WORKDIR /workspace
ENV JAVA_OPTS="-Djava.net.preferIPv4Stack=true"
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx768m -Dorg.gradle.workers.max=1"
COPY gradle gradle
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon clean check bootJar

FROM eclipse-temurin:26-jre AS extract

WORKDIR /builder
COPY --from=build /workspace/build/libs/ods-sso-backend-2.0.0.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:26-jre

RUN sed -i \
      -e 's|http://archive\.ubuntu\.com/ubuntu|https://ru-msk1.clouds.archive.ubuntu.com/ubuntu|g' \
      -e 's|http://security\.ubuntu\.com/ubuntu|https://ru-msk1.clouds.archive.ubuntu.com/ubuntu|g' \
      /etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true \
    && apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=5 update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --create-home app

WORKDIR /app
COPY --from=extract --chown=app:app /builder/extracted/dependencies/ ./
COPY --from=extract --chown=app:app /builder/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=app:app /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=app:app /builder/extracted/application/ ./

USER app
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD ["curl", "--fail", "http://127.0.0.1:8000/health"]

ENTRYPOINT ["java", "-Xms128m", "-Xmx512m", "-XX:+UseG1GC", "-jar", "/app/application.jar"]
