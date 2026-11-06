# Liberica 17 plus the OpenJFX 17 SDK jars on /opt/javafx/lib. Run apps with
# --module-path /opt/javafx/lib --add-modules javafx.controls
FROM java-apps/jdk17:latest
ARG FX_VERSION=17.0.13
RUN mkdir -p /opt/javafx/lib && cd /opt/javafx/lib && \
    for m in base graphics controls swing; do \
      curl -fsSLo javafx-$m.jar "https://repo1.maven.org/maven2/org/openjfx/javafx-$m/$FX_VERSION/javafx-$m-$FX_VERSION-linux.jar"; \
    done
