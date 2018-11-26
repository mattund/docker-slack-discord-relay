package local.mdc.util.dockerslackrelay;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.io.IOUtils;

public class DockerSlackRelay implements Runnable {
    private static final String WEBHOOK_URL = "https://canary.discordapp.com/api/webhooks/%s/%s?wait=true";
    private final HttpServer server;
    private final Map<String, Webhook> webhooks = new LinkedHashMap<>();

    public DockerSlackRelay(int port, int backlog)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), backlog);

        server.createContext("/relay", new Handler());
    }

    public void run() {
        Logger.getGlobal().info("Binding to " + server.getAddress() + "...");

        server.start();

        Logger.getGlobal().info("Server is listening.");

        synchronized (this) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                Logger.getGlobal().warning("Main thread interrupted!");
            }


            Logger.getGlobal().info("Stopping server...");

            server.stop(10);

            Logger.getGlobal().info("Server stopped.");
        }
    }

    class Handler implements HttpHandler {
        public void handle(HttpExchange httpExchange)
                throws IOException {
            try {
                Date date = new Date();

                if (!httpExchange.getRequestMethod().equalsIgnoreCase("post"))
                    throw new IllegalAccessException("invalid HTTP method: " + httpExchange.getRequestMethod());

                String path = httpExchange.getRequestURI().getPath();
                while (path.startsWith("/")) path = path.substring(1);
                String[] args = path.split("\\/");
                if (args.length != 3)
                    throw new IllegalArgumentException("missing id & token: " + httpExchange.getRequestURI().getPath());

                String id = args[1];
                String token = args[2];

                JsonElement element = new JsonParser()
                        .parse(IOUtils.toString(new InputStreamReader(httpExchange.getRequestBody())));

                if (!element.isJsonObject())
                    throw new IllegalArgumentException("POST body is not JSON object");

                JsonObject slackMessage = element.getAsJsonObject();

                JsonObject embed = new JsonObject();
                JsonArray embeds;
                embed.add("embeds", embeds = new JsonArray());

                for (JsonElement attachmentElement : slackMessage.get("attachments").getAsJsonArray()) {
                    JsonObject discordMessage = new JsonObject();

                    TimeZone tz = TimeZone.getTimeZone("UTC");
                    DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'"); // Quoted "Z" to indicate UTC, no timezone offset
                    df.setTimeZone(tz);
                    discordMessage.add("timestamp", new JsonPrimitive(df.format(date)));

                    JsonObject attachment = attachmentElement.getAsJsonObject();

                    discordMessage.add("title", attachment.get("title"));

                    discordMessage.add("url", attachment.get("title_link"));

                    JsonObject image = new JsonObject();
                    image.add("url", attachment.get("image_url"));
                    discordMessage.add("image", image);

                    discordMessage.add("description", attachment.get("text"));

                    JsonElement color = attachment.get("color");
                    if (color != null && !color.isJsonNull()) {
                        String colorString = color.getAsString();
                        discordMessage.add("color", new JsonPrimitive(Long.decode(colorString)));
                    }

                    JsonElement fieldsElement = attachment.get("fields");
                    JsonArray fields = new JsonArray();
                    if (fieldsElement != null && !fieldsElement.isJsonNull()) {
                        for (JsonElement fieldElement : fieldsElement.getAsJsonArray()) {
                            JsonObject field = fieldElement.getAsJsonObject();
                            JsonObject item = new JsonObject();

                            item.add("name", new JsonPrimitive(field.get("title").getAsJsonPrimitive().getAsString()));
                            item.add("value", new JsonPrimitive(field.get("value").getAsJsonPrimitive().getAsString()));

                            fields.add(item);
                        }
                    }
                    discordMessage.add("fields", fields);

                    JsonObject footer = new JsonObject();
                    footer.add("footer_icon", attachment.get("footer_icon"));
                    footer.add("text", attachment.get("footer"));

                    discordMessage.add("footer", footer);
                    embeds.add(discordMessage);
                }

                Webhook queue;
                String key = id + ":" + token;

                synchronized (webhooks) {
                    queue = webhooks.computeIfAbsent(key, s -> new Webhook(id, token));
                }

                if (embeds.size() > 0) queue.submit(embed);

                String reply = "submitted " + embeds.size() + " embeds";
                httpExchange.sendResponseHeaders(200, reply.length());
                IOUtils.write(reply, httpExchange.getResponseBody(), Charset.forName("UTF8"));
            } catch (Throwable ex) {
                Logger.getGlobal().log(Level.WARNING, "Problem processing webhook", ex);

                String reply = ex.getMessage();
                httpExchange.sendResponseHeaders(500, reply.length());
                IOUtils.write(reply, httpExchange.getResponseBody(), Charset.forName("UTF8"));
            } finally {
                httpExchange.close();
            }
        }
    }

    private void sendWebhook(String id, String token, JsonElement embeds) {
        String url = String.format(WEBHOOK_URL, id, token);
        boolean exit = false;

        for (int i =0; !exit; i++) {
            try {
                String body = embeds.toString();

                Logger.getGlobal().info("[POST] " + url + " " + body);

                HttpURLConnection urlConnection = (HttpURLConnection)
                        URI.create(url).toURL().openConnection();

                urlConnection.setRequestMethod("POST");
                urlConnection.setConnectTimeout(10000);
                urlConnection.setDoOutput(true);
                urlConnection.setDefaultUseCaches(false);
                urlConnection.setRequestProperty("User-Agent", "discord-slack-webhook");
                urlConnection.setReadTimeout(30000);

                urlConnection.setRequestProperty("Content-Type", "application/json");
                urlConnection.setRequestProperty("Content-Length", Integer.toString(body.length()));

                try (OutputStream outputStream = urlConnection.getOutputStream()) {
                    IOUtils.write(body, outputStream, Charset.forName("UTF8"));
                }

                int responseCode = urlConnection.getResponseCode();

                if (responseCode / 100 == 2) {
                    String response;
                    try (InputStream inputStream = urlConnection.getInputStream()) {
                        response = IOUtils.toString(new InputStreamReader(inputStream));
                    }

                    Logger.getGlobal().info("Successfully dispatched webhook: " + response);
                    exit = true;
                    break;
                } else {
                    switch (responseCode) {
                        case 403:
                        case 400:
                            exit = true;
                            break;
                    }

                    throw new IOException(responseCode + " " + urlConnection.getResponseMessage() + " " +
                            IOUtils.toString(new InputStreamReader(urlConnection.getErrorStream())));
                }
            } catch (Exception ex) {
                Logger.getGlobal().log(exit ? Level.SEVERE : Level.WARNING,
                        "Problem sending webhook (num=" + i + ", " + (exit ? "giving up" : "will retry")
                                + ": " + ex.getMessage());

                long sleep = Math.max(0, Math.min(120000L, (i-1) * 1000L));

                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }
    }

    private class Webhook implements Runnable {
        private final String
                id,
                token;

        private final Object submissionLock = new Object();

        private Thread thread;

        private final BlockingDeque<JsonElement> blockingDeque = new LinkedBlockingDeque<>();

        private Webhook(String id, String token) {
            this.id = id;
            this.token = token;
        }

        public String getId() {
            return id;
        }

        public String getToken() {
            return token;
        }

        @Override
        public boolean equals(Object b) {
            return b instanceof Webhook && b == this;
        }

        @Override
        public int hashCode() {
            return id.hashCode() ^ token.hashCode();
        }

        public void submit(JsonElement embed) {
            Logger.getGlobal().info("[QUEUE] " + embed.toString());

            synchronized (submissionLock) {
                blockingDeque.add(embed);

                if (thread == null || !thread.isAlive()) {
                    thread = new Thread(this);
                    thread.setName("Queue-" + id);
                    thread.setDaemon(true);
                    thread.start();
                }
            }
        }

        @Override
        public void run() {
            synchronized (this) {
                while (blockingDeque.size() > 0)
                    DockerSlackRelay.this.sendWebhook(id, token, blockingDeque.remove());
            }
        }
    }

    public static void main(String[] args) {
        final Logger logger = Logger.getGlobal();

        try {
            logger.setUseParentHandlers(false);

            ConsoleHandler handler = new ConsoleHandler();
            handler.setFormatter(new LineLogFormatter());
            handler.setLevel(Level.ALL);
            logger.addHandler(handler);

            logger.info("Starting relay...");

            JsonObject configuration = new JsonParser().parse(
                    new FileReader("config.json")
            ).getAsJsonObject();

            int port = configuration.get("port").getAsInt();
            int backlog = configuration.get("backlog").getAsInt();

            final DockerSlackRelay relay = new DockerSlackRelay(port, backlog);
            final Thread main = Thread.currentThread();

            Thread shutdownThread = new Thread(() -> {
                synchronized (relay) {
                    logger.warning("Received termination signal; shutting down...");
                    main.interrupt();
                }
            });

            shutdownThread.setName("Shutdown");
            shutdownThread.setDaemon(true);

            Runtime.getRuntime().addShutdownHook(shutdownThread);

            synchronized (relay) {
                relay.run();
            }
        } catch (Throwable ex) {
            logger.log(Level.SEVERE, "Problem starting relay", ex);
        } finally {
            Logger.getGlobal().info("Exiting normally");

            System.exit(0);
        }
    }
}
