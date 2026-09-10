FROM eclipse-temurin:21-jdk-jammy AS build

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline

COPY src src
RUN mvn package -DskipTests

FROM eclipse-temurin:21-jre-jammy

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y ffmpeg && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/music-downloader-1.0.0.jar app.jar

VOLUME /music/downloads
VOLUME /config

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]