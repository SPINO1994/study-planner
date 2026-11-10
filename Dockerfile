FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 planner && useradd --uid 10001 --gid planner planner
COPY --from=build --chown=planner:planner /build/target/study-planner-0.1.0.jar app.jar
USER planner
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
