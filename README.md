# docker-slack-discord-relay

This project is for those of you who have applications like Grafana, which can push notifications to a Slack server, but do not support Discord entirely.  Discord has API ingestion calls for Slack (`/slack` on the webhook URL), but they do not always work exactly right depending on the calling application.  This project solves this problem with a nice little docker container which listens on port 8888.

This relay also **buffers requests** and resends requests on failure, **ensuring all notifications are pushed eventually** if connectivity to the Discord API endpoint is lost.

# Building

Here is my GitLab CI YML:

```
stages:
- compile
- compose
- deploy

variables:
  MAVEN_CLI_OPTS: "--batch-mode"
  MAVEN_OPTS: "-Dmaven.repo.local=.m2/repository"

cache:
  key: ${CI_COMMIT_REF_SLUG}
  paths:
  - .m2/repository/

compile:
  stage: compile
  image: maven:latest
  script:
  - mvn $MAVEN_CLI_OPTS package
  - rm -rf target/classes
  artifacts:
    paths:
    - target/

compose:
  stage: compose
  image: docker:latest
  script:
  - docker build -t mattund/java-docker-slack-relay .

deploy:
  stage: deploy
  image: docker:latest
  script:
  - docker stop java-docker-slack-relay || true && docker rm java-docker-slack-relay || true
  - docker run --restart unless-stopped -d -p 8888:8888 --name java-docker-slack-relay mattund/java-docker-slack-relay /opt/java-docker-slack-relay/run.sh
  dependencies: []
```
