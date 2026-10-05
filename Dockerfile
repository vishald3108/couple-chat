FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY Main.java .
CMD ["java", "Main.java"]
