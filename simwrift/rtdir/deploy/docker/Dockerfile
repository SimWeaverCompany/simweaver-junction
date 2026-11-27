# SimWeaver Junction - container image. The installation guide, 1c, has the details.
#
# Build from the program folder (the unpacked zip):
#   docker build -f deploy/docker/Dockerfile -t simweaver-junction:0.14.5 .
# Run, with the site in the volume junction-site and the RTI mounted at /opt/rti (leave that mount out
# for DIS only):
#   docker run -d --name simweaver-junction --network host \
#     -v junction-site:/var/lib/simweaver-junction -v /opt/anrti:/opt/rti:ro simweaver-junction:0.14.5
# or: docker compose -f deploy/docker/compose.yaml up -d
#
# The program is in /opt/simweaver/junction, owned by root and read-only to the server; the site
# (settings, logs, key store) is the volume /var/lib/simweaver-junction, made on the first start from the
# program's etc.dist. A new image replaces the program and keeps the site. The server runs as the user
# 10001 (simweaver); a host folder mounted as the site must be writable by it:
#   chown -R 10001:10001 /srv/simweaver-junction

# another Java 11 or later image: docker build --build-arg BASE=eclipse-temurin:17-jre ...
ARG BASE=eclipse-temurin:11-jre
FROM ${BASE}

COPY . /opt/simweaver/junction
# Windows line endings out of the start script; no site of a console run in the image; the user and the
# site folder
RUN set -e; P=/opt/simweaver/junction; \
    sed -i 's/\r$//' "$P/run.sh"; chmod 755 "$P/run.sh"; \
    rm -rf "$P/etc" "$P/logs" "$P/log" "$P/cache" "$P/.lock" "$P/keystore.jks"; \
    chown -R root:root "$P"; chmod -R go-w "$P"; \
    grep -q '^simweaver:' /etc/group  || echo 'simweaver:x:10001:' >> /etc/group; \
    grep -q '^simweaver:' /etc/passwd || echo 'simweaver:x:10001:10001:SimWeaver Junction:/var/lib/simweaver-junction:/usr/sbin/nologin' >> /etc/passwd; \
    mkdir -p /var/lib/simweaver-junction; chown 10001:10001 /var/lib/simweaver-junction; chmod 750 /var/lib/simweaver-junction

USER 10001:10001
WORKDIR /var/lib/simweaver-junction
# the RTI's library inside the mount (a placeholder: change it, or give -e RTI_JAR=... at run; empty for
# DIS only)
ENV RTI_JAR=/opt/rti/lib/hlaimpl.jar \
    JUNCTION_SITE=/var/lib/simweaver-junction
VOLUME ["/var/lib/simweaver-junction"]
# the web server and the API (HTTPS); J2J (TCP and UDP) when it is on
EXPOSE 8843 8844 8844/udp

# --health asks the running server over HTTPS (any certificate) and ends with 0 or 1; a small Java heap
# of its own, whatever JAVA_OPTS says
HEALTHCHECK --interval=30s --timeout=15s --start-period=60s --retries=3 \
    CMD JAVA_OPTS="-Xmx64m -XX:TieredStopAtLevel=1" /bin/sh /opt/simweaver/junction/run.sh --root=/var/lib/simweaver-junction --health || exit 1

# exec form: Java is process 1 and gets the stop signal (it resigns and logs before it ends)
ENTRYPOINT ["/bin/sh", "/opt/simweaver/junction/run.sh", "--root=/var/lib/simweaver-junction"]
