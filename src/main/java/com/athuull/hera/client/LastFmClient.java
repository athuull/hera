package com.athuull.hera.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.athuull.hera.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LastFmClient {

    private final RestTemplate restTemplate;
    private final SettingsService settingsService;

    @Value("${lastfm.api-root}")
    private String apiRoot;

    @Value("${lastfm.format}")
    private String format;

    private static class CacheEntry {
        final JsonNode data;
        final long expiresAt;
        CacheEntry(JsonNode data, long ttlMs) {
            this.data = data;
            this.expiresAt = System.currentTimeMillis() + ttlMs;
        }
        boolean isValid() {
            return System.currentTimeMillis() < expiresAt;
        }
    }

    private final Map<String, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();

    // Last.fm API request tracking & compliance metrics
    private final java.util.concurrent.atomic.AtomicLong totalRequests = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong networkCalls = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong cacheHits = new java.util.concurrent.atomic.AtomicLong(0);

    // Limit concurrent requests to Last.fm to 5 to strictly respect Last.fm TOS rate limits
    private final java.util.concurrent.Semaphore rateLimiter = new java.util.concurrent.Semaphore(5);

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LastFmClient.class);

    private long getTtlForMethod(String method) {
        if (method.startsWith("artist.") || method.startsWith("tag.")) {
            return 3600_000L; // 1 hour for artist/tag metadata
        }
        if (method.startsWith("user.getTop")) {
            return 300_000L; // 5 minutes for user top charts
        }
        return 0L;
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        long total = totalRequests.get();
        long net = networkCalls.get();
        long hits = cacheHits.get();
        stats.put("totalRequests", total);
        stats.put("networkCalls", net);
        stats.put("cacheHits", hits);
        double hitRate = total > 0 ? ((double) hits / total) * 100.0 : 0.0;
        stats.put("cacheHitPercentage", Math.round(hitRate * 10.0) / 10.0);
        stats.put("cachedEntries", cache.size());
        return stats;
    }

    public JsonNode get(String method, Map<String, String> params) {
        totalRequests.incrementAndGet();
        long ttl = getTtlForMethod(method);
        String cacheKey = (ttl > 0) ? (method + ":" + (params != null ? params.toString() : "")) : null;
        if (cacheKey != null) {
            CacheEntry entry = cache.get(cacheKey);
            if (entry != null && entry.isValid()) {
                cacheHits.incrementAndGet();
                log.debug("Last.fm cache HIT for '{}' (hits: {}, network: {})", method, cacheHits.get(), networkCalls.get());
                return entry.data;
            }
        }

        long reqNum = networkCalls.incrementAndGet();
        log.info("Last.fm API request #{} -> method: {}", reqNum, method);

        String apiKey = settingsService.getSettings().getLastfmApiKey();
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(apiRoot)
                .queryParam("method", method)
                .queryParam("api_key", apiKey)
                .queryParam("format", format);

        if (params != null) params.forEach(builder::queryParam);

        try {
            rateLimiter.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for Last.fm rate limiter", ie);
        }

        ResponseEntity<JsonNode> response;
        try {
            response = restTemplate.getForEntity(builder.build().toUriString(), JsonNode.class);
        } finally {
            rateLimiter.release();
        }
        JsonNode body = response.getBody();

        if (body == null) throw new RuntimeException("Empty response from Last.fm for method: " + method);
        if (body.has("error")) {
            int code = body.get("error").asInt();
            String msg = body.has("message") ? body.get("message").asText() : "Unknown";
            throw new RuntimeException("Last.fm error [" + code + "]: " + msg);
        }

        if (cacheKey != null) {
            cache.put(cacheKey, new CacheEntry(body, ttl));
        }

        return body;
    }

    public JsonNode get(String method) {
        return get(method, null);
    }

    private JsonNode call(String method, Object... kvs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            if (kvs[i] != null && kvs[i + 1] != null) {
                Object val = kvs[i + 1];
                m.put(kvs[i].toString(), val instanceof Boolean b ? (b ? "1" : "0") : val.toString());
            }
        }
        return get(method, m);
    }

    // ═══ ARTIST ═══
    public JsonNode artistGetSimilar(String artist, String mbid, Boolean autocorrect, Integer limit) {
        return call("artist.getSimilar", mbid != null ? "mbid" : "artist", mbid != null ? mbid : artist,
                "autocorrect", autocorrect, "limit", limit);
    }
    public JsonNode artistGetSimilar(String artist, int limit) { return artistGetSimilar(artist, null, true, limit); }

    public JsonNode artistGetTopTags(String artist, String mbid, Boolean autocorrect) {
        return call("artist.getTopTags", mbid != null ? "mbid" : "artist", mbid != null ? mbid : artist, "autocorrect", autocorrect);
    }
    public JsonNode artistGetTopTags(String artist) { return artistGetTopTags(artist, null, true); }

    public JsonNode artistGetTopTracks(String artist, String mbid, Boolean autocorrect, Integer page, Integer limit) {
        return call("artist.getTopTracks", mbid != null ? "mbid" : "artist", mbid != null ? mbid : artist,
                "autocorrect", autocorrect, "page", page, "limit", limit);
    }
    public JsonNode artistGetTopTracks(String artist, int limit) { return artistGetTopTracks(artist, null, true, 1, limit); }

    // ═══ TRACK ═══
    public JsonNode trackGetSimilar(String artist, String track, String mbid, Boolean autocorrect, Integer limit) {
        return mbid != null
                ? call("track.getSimilar", "mbid", mbid, "autocorrect", autocorrect, "limit", limit)
                : call("track.getSimilar", "artist", artist, "track", track, "autocorrect", autocorrect, "limit", limit);
    }
    public JsonNode trackGetSimilar(String artist, String track, int limit) { return trackGetSimilar(artist, track, null, true, limit); }

    // ═══ USER ═══
    public JsonNode userGetTopArtists(String user, String period, Integer page, Integer limit) {
        return call("user.getTopArtists", "user", user, "period", period, "page", page, "limit", limit);
    }
    public JsonNode userGetTopArtists(String user, String period, int limit) { return userGetTopArtists(user, period, 1, limit); }

    public JsonNode userGetRecentTracks(String user, Boolean extended, Integer page, Integer limit, Long from, Long to) {
        return call("user.getRecentTracks", "user", user, "extended", extended, "page", page, "limit", limit, "from", from, "to", to);
    }
    public JsonNode userGetRecentTracks(String user, int limit) { return userGetRecentTracks(user, true, 1, limit, null, null); }

    // ═══ TAG ═══
    public JsonNode tagGetTopTracks(String tag, Integer page, Integer limit) {
        return call("tag.getTopTracks", "tag", tag, "page", page, "limit", limit);
    }
    public JsonNode tagGetTopTracks(String tag, int limit) { return tagGetTopTracks(tag, 1, limit); }
}
