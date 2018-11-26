#!/bin/bash

cd /opt/java-docker-slack-relay/
sleep 2

echo "Launching Docker Slack Relay..."
java -server \
     -jar target/docker-slack-relay-1.0-SNAPSHOT.jar
