# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY common/pom.xml common/
COPY music/pom.xml music/
COPY comic/pom.xml comic/
COPY video/pom.xml video/
COPY ebook/pom.xml ebook/
COPY app/pom.xml app/
RUN mvn dependency:go-offline -B
COPY . .
RUN mvn clean package -DskipTests -B

# Stage 2: Run
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir -p /app/data
COPY --from=build /app/app/target/*.jar app.jar
EXPOSE 20058
ENTRYPOINT ["java", "-jar", "app.jar"]
