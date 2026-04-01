FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

COPY build/libs/malikov-backend.jar app.jar

HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD wget -qO- http://localhost:8080/health || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
