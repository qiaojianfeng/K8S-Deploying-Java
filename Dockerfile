FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu@sha256:d4cb55376795c0facef7ff9e8cfd60dabdf792c7f8c7a26bb22c9a3e34d9b06e

LABEL org.opencontainers.image.source="https://github.com/sunweisheng/K8S-Deploying-Java"

WORKDIR /app

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app

COPY --chown=10001:10001 target/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
