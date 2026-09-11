# One build for the whole reactor. Both service stages copy out of this one, so it runs once.
FROM eclipse-temurin:26-jdk-alpine AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY apps apps
# Cache mount rather than a copy-poms-first dance: one line, and it survives changes to the module list.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

# Shared runtime: a JRE and an unprivileged user, so neither service runs as root.
FROM eclipse-temurin:26-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
USER app

FROM runtime AS auth-service
COPY --from=build /src/apps/auth-service/target/auth-service-*.jar app.jar
EXPOSE 9081
CMD ["java", "-jar", "app.jar"]

FROM runtime AS todo-service
COPY --from=build /src/apps/todo-service/target/todo-service-*.jar app.jar
EXPOSE 9082
CMD ["java", "-jar", "app.jar"]

# No dependencies to install: server.js is plain node with an empty package.json.
FROM node:22-alpine AS web
WORKDIR /app
COPY apps/web ./
USER node
EXPOSE 3000
CMD ["node", "server.js"]
