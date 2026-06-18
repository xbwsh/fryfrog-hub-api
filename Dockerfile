FROM docker.1ms.run/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common/pom.xml common/
COPY music/pom.xml music/
COPY comic/pom.xml comic/
COPY ebook/pom.xml ebook/
COPY video/pom.xml video/
COPY app/pom.xml app/
RUN mvn dependency:go-offline -B
COPY . .
RUN mvn clean package -DskipTests -B

FROM docker.1ms.run/eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/app/target/fryfrog-hub-app-*.jar app.jar
RUN mkdir -p /data /app/media-library
EXPOSE 20058
ENTRYPOINT ["java", "-jar", "app.jar"]
