# docker-slack-discord-relay

This project is for those of you who have applications like Grafana, which can push notifications to a Slack server, but do not support Discord entirely.  Discord has API ingestion calls for Slack (`/slack` on the webhook URL), but they do not always work exactly right depending on the calling application.  This project solves this problem with a nice little docker container which listens on port 8888.
