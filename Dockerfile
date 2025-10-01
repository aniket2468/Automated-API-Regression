FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
COPY examples ./examples
RUN mvn -B -ntp verify

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S apiguard && adduser -S apiguard -G apiguard
WORKDIR /app
RUN mkdir /app/data && chown -R apiguard:apiguard /app
COPY --from=build /workspace/target/api-regression-platform-1.0.0.jar app.jar
USER apiguard
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
VOLUME /app/data
ENTRYPOINT ["java", "-jar", "app.jar"]
