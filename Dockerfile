# ── Backend image (Kotlin/Ktor) ───────────────────────────────────────────────
# Stage 1: build with the Gradle wrapper on a full JDK.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon --no-configuration-cache :server:installDist -x test

# Stage 2: slim runtime.
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 app
COPY --from=build /src/server/build/install/server /opt/server
# Seed data the engine reads from its working directory. Copied into the /data volume on
# first start only, so runtime-written files (known_loadouts.json, logs/) persist across deploys.
COPY all_maps.json all_monsters.json all_teleport_potions.json known_loadouts.json event_config.json raid_config.json /opt/seed/
COPY deploy/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && mkdir /data && chown app /data
USER app
WORKDIR /data
VOLUME /data
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD curl -fs http://localhost:8080/api/health || exit 1
ENTRYPOINT ["/entrypoint.sh"]
