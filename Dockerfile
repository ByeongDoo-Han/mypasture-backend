FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x ./gradlew
RUN ./gradlew dependencies --no-daemon

COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN groupadd --system mypasture && useradd --system --gid mypasture mypasture
COPY --from=build /workspace/build/libs/*.jar /app/mypasture.jar
COPY --chown=mypasture:mypasture data/kor_html /app/data/kor_html
USER mypasture

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/mypasture.jar"]
