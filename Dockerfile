FROM maven:3.9.11-eclipse-temurin-11 AS build
WORKDIR /app

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests -Dproject.build.sourceEncoding=UTF-8 clean package

FROM eclipse-temurin:11-jre
ARG APP_UID=1000
ARG APP_GID=1000
WORKDIR /app
ENV LANG=ko_KR.UTF-8
ENV LC_ALL=ko_KR.UTF-8
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"

RUN if ! getent group ubuntu >/dev/null; then groupadd --gid ${APP_GID} ubuntu; fi \
    && if ! id -u ubuntu >/dev/null 2>&1; then useradd --uid ${APP_UID} --gid ubuntu --create-home --shell /bin/bash ubuntu; fi \
    && mkdir -p /app/uploads \
    && chown -R ubuntu:ubuntu /app

COPY --from=build --chown=ubuntu:ubuntu /app/target/*.jar /app/app.jar

EXPOSE 8080
USER ubuntu
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
