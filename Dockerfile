FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY peakdispatch-domain/pom.xml peakdispatch-domain/pom.xml
COPY peakdispatch-application/pom.xml peakdispatch-application/pom.xml
COPY peakdispatch-infrastructure/pom.xml peakdispatch-infrastructure/pom.xml
COPY peakdispatch-api-worker/pom.xml peakdispatch-api-worker/pom.xml
RUN mvn -B -ntp -pl peakdispatch-api-worker -am dependency:go-offline
COPY peakdispatch-domain peakdispatch-domain
COPY peakdispatch-application peakdispatch-application
COPY peakdispatch-infrastructure peakdispatch-infrastructure
COPY peakdispatch-api-worker peakdispatch-api-worker
RUN mvn -B -ntp -pl peakdispatch-api-worker -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget
RUN addgroup -S peakdispatch && adduser -S peakdispatch -G peakdispatch
WORKDIR /app
COPY --from=build /workspace/peakdispatch-api-worker/target/peakdispatch-api-worker-0.1.0-SNAPSHOT.jar /app/app.jar
USER peakdispatch
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
