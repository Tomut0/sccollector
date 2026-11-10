package scstats.collector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedTest {
    @Test
    void retainsStableIdsOnRetryAndOnlyAcknowledgesTheSentEvents() {
        Feed feed = new Feed();
        feed.record("clan.verified", "clanExternalId", "test");
        List<Map<String, Object>> inFlight = feed.batch();
        assertEquals(inFlight, feed.batch());

        feed.record("clan.balanceChanged", "amount", 100);
        feed.acknowledge(inFlight);
        assertEquals(1, feed.batch().size());
        assertEquals("clan.balanceChanged", feed.batch().get(0).get("type"));
    }

    @Test
    void acknowledgingAnEvictedBatchLeavesNewEventsIntact() {
        Feed feed = new Feed();
        feed.record("first");
        List<Map<String, Object>> inFlight = feed.batch();
        for (int i = 0; i < 1000; i++) {
            feed.record("new");
        }
        feed.acknowledge(inFlight);
        assertEquals(1000, feed.batch().size());
        assertEquals("new", feed.batch().get(0).get("type"));
    }
}
