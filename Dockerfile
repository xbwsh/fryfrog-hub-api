ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine

FROM ${MAVEN_IMAGE} AS build
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

FROM ${RUNTIME_IMAGE}
WORKDIR /app
COPY --from=build /workspace/app/target/fryfrog-hub-app-*.jar app.jar
RUN mkdir -p /data /app/data /app/media-library
EXPOSE 20058
ENTRYPOINT ["java", "-jar", "app.jar"]
