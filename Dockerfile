FROM node:24.13.1-alpine AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-alpine AS backend-build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
COPY --from=frontend-build /workspace/frontend/dist ./src/main/resources/static
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S converge && adduser -S converge -G converge
WORKDIR /app
COPY --from=backend-build /workspace/build/libs/converge-*.jar app.jar
USER converge
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

