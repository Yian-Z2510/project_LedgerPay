FROM maven:3.9.11-eclipse-temurin-21-alpine AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S ledgerpay \
    && adduser -S -D -H -G ledgerpay ledgerpay

WORKDIR /app

COPY --from=build --chown=ledgerpay:ledgerpay \
    /workspace/target/ledgerpay-0.0.1-SNAPSHOT.jar /app/ledgerpay.jar

USER ledgerpay

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/ledgerpay.jar"]
