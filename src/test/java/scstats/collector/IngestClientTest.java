package scstats.collector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static scstats.collector.Snapshot.object;

class IngestClientTest {
    @Test
    void postsAnAuthenticatedUtf8SnapshotAndRequiresAcknowledgment() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<JsonObject> payload = new AtomicReference<>();
        AtomicReference<String> reply = new AtomicReference<>("{\"accepted\":true,\"processedAt\":\"2026-11-10T12:00:00Z\"}");
        AtomicInteger status = new AtomicInteger(202);
        AtomicInteger redirects = new AtomicInteger();
        server.createContext("/api/v1/ingest", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            method.set(exchange.getRequestMethod());
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = exchange.getRequestBody().read(buffer)) != -1) {
                request.write(buffer, 0, read);
            }
            payload.set(JsonParser.parseString(request.toString("UTF-8")).getAsJsonObject());
            exchange.getResponseHeaders().add("Location", "/redirect");
            byte[] response = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            redirects.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            IngestClient client = new IngestClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/ingest", "sc-test");
            Map<String, Object> snapshot = object("takenAt", "2026-11-10T12:00:00Z", "clans",
                    Collections.emptyList(), "players", Collections.singletonList(object("name", "Игрок", "clanExternalId", null)));

            client.send(snapshot);
            assertEquals("POST", method.get());
            assertEquals("sc-test", key.get());
            assertEquals("2026-11-10T12:00:00Z", payload.get().get("takenAt").getAsString());
            JsonObject player = payload.get().getAsJsonArray("players").get(0).getAsJsonObject();
            assertEquals("Игрок", player.get("name").getAsString());
            assertTrue(player.get("clanExternalId").isJsonNull());

            reply.set("{\"accepted\":false}");
            assertThrows(IOException.class, () -> client.send(snapshot));
            status.set(307);
            assertThrows(IOException.class, () -> client.send(snapshot));
            assertEquals(0, redirects.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnencryptedRemoteEndpointsAndInvalidCredentials() {
        assertThrows(IllegalArgumentException.class, () -> new IngestClient("http://example.com/ingest", "secret"));
        assertThrows(IllegalArgumentException.class, () -> new IngestClient("https://example.com/ingest", "bad\nkey"));
    }
}
