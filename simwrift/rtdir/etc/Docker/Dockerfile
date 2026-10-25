# SimWeaver Rift - container image. Build from the installation folder:
#   docker build -f etc/Docker/Dockerfile -t simweaver-rift .
# Run, with the RTI mounted at /opt/rti (leave the mount out for DIS only):
#   docker run -d --name simweaver-rift --network host -v /opt/prti1516e:/opt/rti:ro simweaver-rift

FROM eclipse-temurin:11-jre
WORKDIR /opt/simweaver-rift
COPY . .
# strip Windows line endings from the start script
RUN sed -i 's/\r$//' run.sh && chmod 755 run.sh \
 && useradd --system --home-dir /opt/simweaver-rift simweaver && chown -R simweaver /opt/simweaver-rift
USER simweaver
ENV RTI_JAR=/opt/rti/lib/prti1516e.jar
EXPOSE 8843
ENTRYPOINT ["./run.sh"]
