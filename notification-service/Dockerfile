FROM gradle:8.10-jdk21-alpine AS builder
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-Xmx256m", "-Xms64m", "-jar", "app.jar"]
