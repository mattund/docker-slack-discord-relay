FROM openjdk:8-slim
ADD docker/resources /opt/java-docker-slack-relay
RUN chmod +x /opt/java-docker-slack-relay/run.sh
ADD target /opt/java-docker-slack-relay/target
