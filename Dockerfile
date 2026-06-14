FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY app/target/fryfrog-hub-app-*.jar app.jar

RUN mkdir -p /data /app/media-library

EXPOSE 20058

ENTRYPOINT ["java", "-jar", "app.jar"]
