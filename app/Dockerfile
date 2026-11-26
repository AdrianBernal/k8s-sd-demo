# Etapa 1: compilar
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY src/*.java ./
RUN javac -d /out *.java

# Etapa 2: imagen final, solo con el JRE
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /out/ ./
USER 10001
EXPOSE 5000
# Por defecto arranca el servidor; para el cliente: args = EchoClient echo 5000
ENTRYPOINT ["java", "-cp", "/app"]
CMD ["EchoServer"]
