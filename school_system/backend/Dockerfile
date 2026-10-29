FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
RUN groupadd --system school && useradd --system --gid school school && mkdir -p /data/files && chown -R school:school /data
WORKDIR /app
COPY --from=build /workspace/target/school-system-0.1.0.jar app.jar
USER school
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
