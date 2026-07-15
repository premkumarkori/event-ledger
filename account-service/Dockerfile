FROM eclipse-temurin:17-jre

WORKDIR /app

RUN mkdir -p /data/account \
    && chown -R 10001:0 /app /data/account

COPY --chown=10001:0 account-service/target/account-service-0.1.0-SNAPSHOT-exec.jar /app/app.jar

USER 10001

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
