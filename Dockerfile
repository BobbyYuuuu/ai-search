FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY src ./src
COPY public ./public
RUN javac -d out src/com/example/*.java
ENV PORT=8080
CMD ["java","-cp","out","com.example.App"]
