# Stage 1: build the jar with the full JDK + Maven wrapper
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

# Stage 2: small runtime image with only the JRE and the jar, running as a non-root user
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 payledger
WORKDIR /app
COPY --from=build /app/target/payledger-*.jar app.jar
USER payledger
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
