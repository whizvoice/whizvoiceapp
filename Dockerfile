FROM openjdk:21-jdk-slim

# Set environment variables
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/35.0.0

# Install system dependencies
RUN apt-get update && apt-get install -y \
    wget \
    unzip \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create Android SDK directory
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools

# Download and install Android Command Line Tools
RUN wget -q https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Accept licenses and install required SDK components
RUN yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "extras;android;m2repository" \
    "extras;google;m2repository"

# Set working directory
WORKDIR /app

# Copy Gradle wrapper and properties first (for better caching)
COPY gradle/ gradle/
COPY gradlew gradlew.bat gradle.properties ./

# Make gradlew executable
RUN chmod +x gradlew

# Pre-download Gradle dependencies (this layer will be cached)
COPY build.gradle.kts settings.gradle.kts ./
COPY app/build.gradle.kts app/
RUN ./gradlew --version

# Copy source code
COPY . .

# Default command
CMD ["./gradlew", "clean", "lintDebug", "test", "--stacktrace", "--parallel", "--build-cache"] 