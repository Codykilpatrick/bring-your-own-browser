# Zulu 8 with JavaFX bundled (the plain Zulu 8 image has no jfxrt.jar).
FROM java-apps/desktop:latest
ARG ZULU_FX_URL=https://cdn.azul.com/zulu/bin/zulu8.96.0.205-ca-fx-jdk8.0.504-linux_x64.tar.gz
RUN mkdir -p /opt/jdk && curl -fsSL "$ZULU_FX_URL" | tar -xz -C /opt/jdk --strip-components=1
ENV JAVA_HOME=/opt/jdk
ENV PATH=/opt/jdk/bin:${PATH}
