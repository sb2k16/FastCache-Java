FROM openjdk:17-jdk-slim

# Set working directory
WORKDIR /app

# Copy gradle files
COPY gradle/ gradle/
COPY gradlew build.gradle gradle.properties settings.gradle ./

# Copy health-checker subproject
COPY health-checker/ health-checker/

# Copy source code
COPY src/ src/

# Build both projects
RUN ./gradlew fatJar -x test
RUN ./gradlew :health-checker:bootJar -x test

# Create directories for logs and data
RUN mkdir -p /app/logs /app/data

# Expose the default FastCache port and health checker port
EXPOSE 6379 8080

# Set environment variables
ENV JAVA_OPTS="-Xmx1g -Xms512m"
ENV FASTCACHE_HOST="0.0.0.0"
ENV FASTCACHE_PORT="6379"

# Default command to run the server (can be overridden by docker-compose)
CMD ["java", "-jar", "build/libs/FastCache-1.0.0-fat.jar", "--host", "0.0.0.0", "--port", "6379"] 