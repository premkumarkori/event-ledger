FROM eclipse-temurin:17-jre

WORKDIR /app

RUN mkdir -p /data/gateway \
    && chown -R 10001:0 /app /data/gateway

COPY --chown=10001:0 event-gateway/target/event-gateway-0.1.0-SNAPSHOT-exec.jar /app/app.jar

USER 10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
