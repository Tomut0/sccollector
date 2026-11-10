package scstats.collector;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static scstats.collector.Snapshot.object;

final class Feed {
    private final Deque<Map<String, Object>> pending = new ArrayDeque<>();

    synchronized void record(String type, Object... details) {
        Map<String, Object> event = object(details);
        event.put("id", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("occurredAt", Instant.now().toString());
        // shortcut: bounded in-memory retries, use a disk journal if events must survive restarts or long outages.
        if (pending.size() == 1000) {
            pending.removeFirst();
        }
        pending.addLast(event);
    }

    synchronized List<Map<String, Object>> batch() {
        return new ArrayList<>(pending);
    }

    synchronized void acknowledge(List<Map<String, Object>> batch) {
        if (batch.isEmpty()) {
            return;
        }
        Object lastId = batch.get(batch.size() - 1).get("id");
        // If the buffer overflowed while sending, that batch has already been evicted.
        if (pending.stream().noneMatch(event -> lastId.equals(event.get("id")))) {
            return;
        }
        while (!lastId.equals(pending.removeFirst().get("id"))) {
            // Remove only through the last acknowledged event.
        }
    }
}
