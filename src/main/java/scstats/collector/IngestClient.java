package scstats.collector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

final class IngestClient {
    private final URI endpoint;
    private final String apiKey;
    private final Gson json = new GsonBuilder().serializeNulls().create();

    IngestClient(String endpoint, String apiKey) {
        try {
            this.endpoint = new URI(endpoint);
        } catch (URISyntaxException invalidUri) {
            throw new IllegalArgumentException("Invalid Ingest endpoint.");
        }
        String host = this.endpoint.getHost();
        String scheme = this.endpoint.getScheme();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host);
        if (host == null || this.endpoint.getUserInfo() != null || this.endpoint.getFragment() != null
                || this.endpoint.getPort() == 0 || this.endpoint.getPort() > 65535
                || !("https".equalsIgnoreCase(scheme) || (loopback && "http".equalsIgnoreCase(scheme)))) {
            throw new IllegalArgumentException("Use an HTTPS endpoint, or HTTP on loopback for local development.");
        }
        if (!apiKey.matches("[!-~]+")) {
            throw new IllegalArgumentException("API key must contain printable ASCII without spaces.");
        }
        this.apiKey = apiKey;
    }

    void send(Map<String, Object> snapshot) throws IOException {
        send(snapshot, null);
    }

    void send(Map<String, Object> snapshot, Logger debugLogger) throws IOException {
        String payload = json.toJson(snapshot);
        if (debugLogger != null) {
            debugLogger.info("[debug] Ingest payload: " + payload);
        }
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-API-Key", apiKey);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(body);
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_ACCEPTED) {
                throw new IOException(String.format(Locale.ROOT, "Ingest returned HTTP %d", status));
            }
            try (InputStreamReader response = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject result = json.fromJson(response, JsonObject.class);
                if (result == null || !result.has("accepted") || !result.get("accepted").isJsonPrimitive()
                        || !result.getAsJsonPrimitive("accepted").isBoolean() || !result.get("accepted").getAsBoolean()) {
                    throw new IOException("Ingest did not acknowledge the snapshot");
                }
            } catch (JsonParseException invalidResponse) {
                throw new IOException("Ingest returned an invalid response");
            }
        } finally {
            connection.disconnect();
        }
    }
}
