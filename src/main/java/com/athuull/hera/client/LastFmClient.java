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

    public JsonNode get(String method, Map<String, String> params) {
        String apiKey = settingsService.getSettings().getLastfmApiKey();
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(apiRoot)
                .queryParam("method", method)
                .queryParam("api_key", apiKey)
                .queryParam("format", format);

        if (params != null) params.forEach(builder::queryParam);

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(builder.build().toUriString(), JsonNode.class);
        JsonNode body = response.getBody();

        if (body == null) throw new RuntimeException("Empty response from Last.fm for method: " + method);
        if (body.has("error")) {
            int code = body.get("error").asInt();
            String msg = body.has("message") ? body.get("message").asText() : "Unknown";
            throw new RuntimeException("Last.fm error [" + code + "]: " + msg);
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
