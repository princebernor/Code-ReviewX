# Build context is the project root (see docker-compose.yml) so the frontend can be copied in.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src/backend
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B -DskipTests package

# glibc-based image: the tree-sitter JNI grammars are built for x86_64/aarch64 Linux (glibc), not musl/Alpine.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 crx
WORKDIR /app/backend
COPY --from=build /src/backend/target/codereviewx-backend-*.jar app.jar
# Only the files the browser needs.
COPY index.html dashboard.html review.html placement.html problem.html history.html progress.html settings.html /app/
COPY css /app/css
COPY js /app/js
COPY data /app/data
COPY assets /app/assets
USER crx
ENV SPRING_PROFILES_ACTIVE=prod CRX_FRONTEND_DIR=/app
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
