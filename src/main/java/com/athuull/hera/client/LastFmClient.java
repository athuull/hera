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
    public JsonNode artistGetInfo(String artist, String mbid, String lang, Boolean autocorrect, String username) {
        return call("artist.getInfo", mbid != null ? "mbid" : "artist", mbid != null ? mbid : artist,
                "lang", lang, "autocorrect", autocorrect, "username", username);
    }
    public JsonNode artistGetInfo(String artist) { return artistGetInfo(artist, null, "en", true, null); }

    public JsonNode artistGetSimilar(String artist, String mbid, Boolean autocorrect, Integer limit) {
        return call("artist.getSimilar", mbid != null ? "mbid" : "artist", mbid != null ? mbid : artist,
                "autocorrect", autocorrect, "limit", limit);
    }
    public JsonNode artistGetSimilar(String artist, int limit) { return artistGetSimilar(artist, null, true, limit); }

    public JsonNode artistGetTopAlbums(String artist, String mbid, Boolean autocorrect, Integer page, Integer limit) {
        return call("artist.getTopAlbums", mbid != null ? "mbid" : "artist", mbid != null ? mbid : artist,
                "autocorrect", autocorrect, "page", page, "limit", limit);
    }
    public JsonNode artistGetTopAlbums(String artist, int limit) { return artistGetTopAlbums(artist, null, true, 1, limit); }

    public JsonNode artistGetTopTags(String artist, String mbid, Boolean autocorrect) {
        return call("artist.getTopTags", mbid != null ? "mbid" : "artist", mbid != null ? mbid : artist, "autocorrect", autocorrect);
    }
    public JsonNode artistGetTopTags(String artist) { return artistGetTopTags(artist, null, true); }

    public JsonNode artistGetTopTracks(String artist, String mbid, Boolean autocorrect, Integer page, Integer limit) {
        return call("artist.getTopTracks", mbid != null ? "mbid" : "artist", mbid != null ? mbid : artist,
                "autocorrect", autocorrect, "page", page, "limit", limit);
    }
    public JsonNode artistGetTopTracks(String artist, int limit) { return artistGetTopTracks(artist, null, true, 1, limit); }

    public JsonNode artistSearch(String name, Integer page, Integer limit) {
        return call("artist.search", "artist", name, "page", page, "limit", limit);
    }
    public JsonNode artistSearch(String name) { return artistSearch(name, 1, 30); }
    public JsonNode artistGetCorrection(String artist) { return call("artist.getCorrection", "artist", artist); }

    // ═══ ALBUM ═══
    public JsonNode albumGetInfo(String artist, String album, String mbid, Boolean autocorrect, String lang, String username) {
        return mbid != null
                ? call("album.getInfo", "mbid", mbid, "autocorrect", autocorrect, "lang", lang, "username", username)
                : call("album.getInfo", "artist", artist, "album", album, "autocorrect", autocorrect, "lang", lang, "username", username);
    }
    public JsonNode albumGetInfo(String artist, String album) { return albumGetInfo(artist, album, null, true, "en", null); }

    public JsonNode albumGetTopTags(String artist, String album, String mbid, Boolean autocorrect) {
        return mbid != null
                ? call("album.getTopTags", "mbid", mbid, "autocorrect", autocorrect)
                : call("album.getTopTags", "artist", artist, "album", album, "autocorrect", autocorrect);
    }
    public JsonNode albumGetTopTags(String artist, String album) { return albumGetTopTags(artist, album, null, true); }

    public JsonNode albumSearch(String album, Integer page, Integer limit) {
        return call("album.search", "album", album, "page", page, "limit", limit);
    }
    public JsonNode albumSearch(String album) { return albumSearch(album, 1, 30); }

    // ═══ TRACK ═══
    public JsonNode trackGetInfo(String artist, String track, String mbid, Boolean autocorrect, String username) {
        return mbid != null
                ? call("track.getInfo", "mbid", mbid, "autocorrect", autocorrect, "username", username)
                : call("track.getInfo", "artist", artist, "track", track, "autocorrect", autocorrect, "username", username);
    }
    public JsonNode trackGetInfo(String artist, String track) { return trackGetInfo(artist, track, null, true, null); }

    public JsonNode trackGetSimilar(String artist, String track, String mbid, Boolean autocorrect, Integer limit) {
        return mbid != null
                ? call("track.getSimilar", "mbid", mbid, "autocorrect", autocorrect, "limit", limit)
                : call("track.getSimilar", "artist", artist, "track", track, "autocorrect", autocorrect, "limit", limit);
    }
    public JsonNode trackGetSimilar(String artist, String track, int limit) { return trackGetSimilar(artist, track, null, true, limit); }

    public JsonNode trackGetTopTags(String artist, String track, String mbid, Boolean autocorrect) {
        return mbid != null
                ? call("track.getTopTags", "mbid", mbid, "autocorrect", autocorrect)
                : call("track.getTopTags", "artist", artist, "track", track, "autocorrect", autocorrect);
    }
    public JsonNode trackGetTopTags(String artist, String track) { return trackGetTopTags(artist, track, null, true); }

    public JsonNode trackSearch(String track, String artist, Integer page, Integer limit) {
        return call("track.search", "track", track, "artist", artist, "page", page, "limit", limit);
    }
    public JsonNode trackSearch(String track) { return trackSearch(track, null, 1, 30); }
    public JsonNode trackSearch(String track, String artist) { return trackSearch(track, artist, 1, 30); }
    public JsonNode trackGetCorrection(String artist, String track) { return call("track.getCorrection", "artist", artist, "track", track); }

    // ═══ USER ═══
    public JsonNode userGetInfo(String user) { return call("user.getInfo", "user", user); }

    public JsonNode userGetTopArtists(String user, String period, Integer page, Integer limit) {
        return call("user.getTopArtists", "user", user, "period", period, "page", page, "limit", limit);
    }
    public JsonNode userGetTopArtists(String user, String period, int limit) { return userGetTopArtists(user, period, 1, limit); }

    public JsonNode userGetTopTracks(String user, String period, Integer page, Integer limit) {
        return call("user.getTopTracks", "user", user, "period", period, "page", page, "limit", limit);
    }
    public JsonNode userGetTopTracks(String user, String period, int limit) { return userGetTopTracks(user, period, 1, limit); }

    public JsonNode userGetLovedTracks(String user, Integer page, Integer limit) {
        return call("user.getLovedTracks", "user", user, "page", page, "limit", limit);
    }
    public JsonNode userGetLovedTracks(String user, int limit) { return userGetLovedTracks(user, 1, limit); }

    public JsonNode userGetRecentTracks(String user, Boolean extended, Integer page, Integer limit, Long from, Long to) {
        return call("user.getRecentTracks", "user", user, "extended", extended, "page", page, "limit", limit, "from", from, "to", to);
    }
    public JsonNode userGetRecentTracks(String user, int limit) { return userGetRecentTracks(user, true, 1, limit, null, null); }

    public JsonNode userGetTopAlbums(String user, String period, Integer page, Integer limit) {
        return call("user.getTopAlbums", "user", user, "period", period, "page", page, "limit", limit);
    }
    public JsonNode userGetTopAlbums(String user, String period, int limit) { return userGetTopAlbums(user, period, 1, limit); }

    public JsonNode userGetTopTags(String user, Integer limit) { return call("user.getTopTags", "user", user, "limit", limit); }

    public JsonNode userGetPersonalTags(String user, String tag, String taggingType, Integer page, Integer limit) {
        return call("user.getPersonalTags", "user", user, "tag", tag, "taggingtype", taggingType, "page", page, "limit", limit);
    }

    public JsonNode userGetFriends(String user, Boolean recenttracks, Integer page, Integer limit) {
        return call("user.getFriends", "user", user, "recenttracks", recenttracks, "page", page, "limit", limit);
    }

    // ═══ TAG ═══
    public JsonNode tagGetInfo(String tag) { return call("tag.getInfo", "tag", tag); }
    public JsonNode tagGetSimilar(String tag) { return call("tag.getSimilar", "tag", tag); }

    public JsonNode tagGetTopAlbums(String tag, Integer page, Integer limit) {
        return call("tag.getTopAlbums", "tag", tag, "page", page, "limit", limit);
    }
    public JsonNode tagGetTopAlbums(String tag, int limit) { return tagGetTopAlbums(tag, 1, limit); }

    public JsonNode tagGetTopArtists(String tag, Integer page, Integer limit) {
        return call("tag.getTopArtists", "tag", tag, "page", page, "limit", limit);
    }
    public JsonNode tagGetTopArtists(String tag, int limit) { return tagGetTopArtists(tag, 1, limit); }

    public JsonNode tagGetTopTracks(String tag, Integer page, Integer limit) {
        return call("tag.getTopTracks", "tag", tag, "page", page, "limit", limit);
    }
    public JsonNode tagGetTopTracks(String tag, int limit) { return tagGetTopTracks(tag, 1, limit); }
    public JsonNode tagGetTopTags(Integer limit) { return call("tag.getTopTags", "limit", limit); }

    // ═══ CHART ═══
    public JsonNode chartGetTopArtists(Integer page, Integer limit) { return call("chart.getTopArtists", "page", page, "limit", limit); }
    public JsonNode chartGetTopTags(Integer page, Integer limit) { return call("chart.getTopTags", "page", page, "limit", limit); }
    public JsonNode chartGetTopTracks(Integer page, Integer limit) { return call("chart.getTopTracks", "page", page, "limit", limit); }

    // ═══ GEO ═══
    public JsonNode geoGetTopArtists(String country, Integer page, Integer limit) {
        return call("geo.getTopArtists", "country", country, "page", page, "limit", limit);
    }
    public JsonNode geoGetTopArtists(String country, int limit) { return geoGetTopArtists(country, 1, limit); }

    public JsonNode geoGetTopTracks(String country, String location, Integer page, Integer limit) {
        return call("geo.getTopTracks", "country", country, "location", location, "page", page, "limit", limit);
    }
    public JsonNode geoGetTopTracks(String country, int limit) { return geoGetTopTracks(country, null, 1, limit); }

    // ═══ LIBRARY ═══
    public JsonNode libraryGetArtists(String user, Integer page, Integer limit) {
        return call("library.getArtists", "user", user, "page", page, "limit", limit);
    }
    public JsonNode libraryGetArtists(String user, int limit) { return libraryGetArtists(user, 1, limit); }
}
