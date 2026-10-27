# Build toolchain of CI (.gitea/workflows/release.yaml, docs/development.md «CI»): JDK 21 (Gradle, the desktop app's
# toolchain and its jpackage) and the Android SDK the TV app compiles against. dpkg-deb for the .deb comes with the base
# image. Gradle itself comes through the wrapper and, like every dependency, lands in /gradle — a BuildKit cache mount of
# release.Dockerfile. Bump the platform together with compileSdk in app/build.gradle.kts.
FROM eclipse-temurin:21-jdk-noble

ARG CMDLINE_TOOLS=16111833
ARG SDK_PACKAGES="platforms;android-37.0 build-tools;36.0.0"

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip \
 && (yes | sdkmanager --licenses > /dev/null) \
 && sdkmanager $SDK_PACKAGES

ENV GRADLE_USER_HOME=/gradle \
    ANDROID_USER_HOME=/gradle/android \
    GRADLE_OPTS=-Dorg.gradle.daemon=false
