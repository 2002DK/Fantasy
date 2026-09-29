# Builds the React frontend and the Spring Boot backend into one image. The backend
# serves the frontend's static files, so the site and API share one origin.

# --- 1. Frontend: static files in /app/frontend/dist ---
FROM node:24-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# --- 2. Backend: executable jar with the frontend under classpath:/static ---
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /app/backend
# Dependencies first so they are cached until pom.xml changes
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY backend/src src
COPY --from=frontend /app/frontend/dist src/main/resources/static
# Tests run in CI (.github/workflows/ci.yml), not in the image build
RUN ./mvnw -B -q package -DskipTests && cp target/backend-*.jar app.jar

# --- 3. Runtime: JRE only, non-root ---
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=backend /app/backend/app.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod
# Sized for a 512 MB container. Measured under a 358 MB cap, the heap used ~80 MB after
# the player sync and a cold trade analysis, while metaspace used ~100 MB outside the
# heap. So the heap gets 60% (~307 MB) to leave room for metaspace, code cache and
# threads; the serial GC has the smallest footprint; smaller stacks save per-thread memory.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:ReservedCodeCacheSize=64m"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
