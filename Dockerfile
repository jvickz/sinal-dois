# syntax=docker/dockerfile:1
FROM node:24.22.3-alpine AS frontend
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY web ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
COPY --from=frontend /web/dist/ ./src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/reserva-estoque-1.0.0.jar /app/reserva-estoque.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/reserva-estoque.jar"]
