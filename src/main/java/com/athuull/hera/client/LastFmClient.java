package com.athuull.hera.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.athuull.hera.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
public class LastFmClient {

    private final RestTemplate restTemplate;
    private final SettingsService settingsService;

    @Value("${lastfm.api-root}")
    private String apiRoot;

    @Value("${lastfm.format}")
    private String format;

    @Autowired
    public LastFmClient(RestTemplate restTemplate, SettingsService settingsService) {
        this.restTemplate = restTemplate;
        this.settingsService = settingsService;
    }

    public JsonNode get(String method, Map<String, String> params) {
        String apiKey = settingsService.getSettings().getLastfmApiKey();

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(apiRoot)
                .queryParam("method", method)
                .queryParam("api_key", apiKey)
                .queryParam("format", format);

        if (params != null) {
            params.forEach(builder::queryParam);
        }

        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                builder.build().toUriString(), JsonNode.class);
        JsonNode body = response.getBody();

        if (body == null) {
            throw new RuntimeException("Empty response from Last.fm for method: " + method);
        }
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

    // ═══ ARTIST ═══

    public JsonNode artistGetInfo(String artist, String mbid, String lang, Boolean autocorrect, String username) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (mbid != null) params.put("mbid", mbid);
        else params.put("artist", artist);
        if (lang != null) params.put("lang", lang);
        if (autocorrect != null) params.put("autocorrect", autocorrect ? "1" : "0");
        if (username != null) params.put("username", username);
        return get("artist.getInfo", params);
    }

    public JsonNode artistGetInfo(String artist) {
        return artistGetInfo(artist, null, "en", true, null);
    }

    public JsonNode artistGetSimilar(String artist, String mbid, Boolean autocorrect, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (mbid != null) params.put("mbid", mbid);
        else params.put("artist", artist);
        if (autocorrect != null) params.put("autocorrect", autocorrect ? "1" : "0");
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("artist.getSimilar", params);
    }

    public JsonNode artistGetSimilar(String artist, int limit) {
        return artistGetSimilar(artist, null, true, limit);
    }

    public JsonNode artistGetTopAlbums(String artist, String mbid, Boolean autocorrect, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (mbid != null) params.put("mbid", mbid);
        else params.put("artist", artist);
        if (autocorrect != null) params.put("autocorrect", autocorrect ? "1" : "0");
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("artist.getTopAlbums", params);
    }

    public JsonNode artistGetTopAlbums(String artist, int limit) {
        return artistGetTopAlbums(artist, null, true, 1, limit);
    }

    public JsonNode artistGetTopTags(String artist, String mbid, Boolean autocorrect) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (mbid != null) params.put("mbid", mbid);
        else params.put("artist", artist);
        if (autocorrect != null) params.put("autocorrect", autocorrect ? "1" : "0");
        return get("artist.getTopTags", params);
    }

    public JsonNode artistGetTopTags(String artist) {
        return artistGetTopTags(artist, null, true);
    }

    public JsonNode artistGetTopTracks(String artist, String mbid, Boolean autocorrect, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (mbid != null) params.put("mbid", mbid);
        else params.put("artist", artist);
        if (autocorrect != null) params.put("autocorrect", autocorrect ? "1" : "0");
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("artist.getTopTracks", params);
    }

    public JsonNode artistGetTopTracks(String artist, int limit) {
        return artistGetTopTracks(artist, null, true, 1, limit);
    }

    public JsonNode artistSearch(String name, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("artist", name);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("artist.search", params);
    }

    public JsonNode artistSearch(String name) {
        return artistSearch(name, 1, 30);
    }

    public JsonNode artistGetCorrection(String artist) {
        return get("artist.getCorrection", Map.of("artist", artist));
    }

    // ═══ ALBUM ═══

    public JsonNode albumGetInfo(String artist, String album, String mbid, Boolean autocorrect, String lang, String username) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (mbid != null) {
            params.put("mbid", mbid);
        } else {
            params.put("artist", artist);
            params.put("album", album);
        }
        if (autocorrect != null) params.put("autocorrect", autocorrect ? "1" : "0");
        if (lang != null) params.put("lang", lang);
        if (username != null) params.put("username", username);
        return get("album.getInfo", params);
    }

    public JsonNode albumGetInfo(String artist, String album) {
        return albumGetInfo(artist, album, null, true, "en", null);
    }

    public JsonNode albumGetTopTags(String artist, String album, String mbid, Boolean autocorrect) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (mbid != null) {
            params.put("mbid", mbid);
        } else {
            params.put("artist", artist);
            params.put("album", album);
        }
        if (autocorrect != null) params.put("autocorrect", autocorrect ? "1" : "0");
        return get("album.getTopTags", params);
    }

    public JsonNode albumGetTopTags(String artist, String album) {
        return albumGetTopTags(artist, album, null, true);
    }

    public JsonNode albumSearch(String album, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("album", album);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("album.search", params);
    }

    public JsonNode albumSearch(String album) {
        return albumSearch(album, 1, 30);
    }

    // ═══ TRACK ═══

    public JsonNode trackGetInfo(String artist, String track, String mbid, Boolean autocorrect, String username) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (mbid != null) {
            params.put("mbid", mbid);
        } else {
            params.put("artist", artist);
            params.put("track", track);
        }
        if (autocorrect != null) params.put("autocorrect", autocorrect ? "1" : "0");
        if (username != null) params.put("username", username);
        return get("track.getInfo", params);
    }

    public JsonNode trackGetInfo(String artist, String track) {
        return trackGetInfo(artist, track, null, true, null);
    }

    public JsonNode trackGetSimilar(String artist, String track, String mbid, Boolean autocorrect, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (mbid != null) {
            params.put("mbid", mbid);
        } else {
            params.put("artist", artist);
            params.put("track", track);
        }
        if (autocorrect != null) params.put("autocorrect", autocorrect ? "1" : "0");
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("track.getSimilar", params);
    }

    public JsonNode trackGetSimilar(String artist, String track, int limit) {
        return trackGetSimilar(artist, track, null, true, limit);
    }

    public JsonNode trackGetTopTags(String artist, String track, String mbid, Boolean autocorrect) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (mbid != null) {
            params.put("mbid", mbid);
        } else {
            params.put("artist", artist);
            params.put("track", track);
        }
        if (autocorrect != null) params.put("autocorrect", autocorrect ? "1" : "0");
        return get("track.getTopTags", params);
    }

    public JsonNode trackGetTopTags(String artist, String track) {
        return trackGetTopTags(artist, track, null, true);
    }

    public JsonNode trackSearch(String track, String artist, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("track", track);
        if (artist != null) params.put("artist", artist);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("track.search", params);
    }

    public JsonNode trackSearch(String track) {
        return trackSearch(track, null, 1, 30);
    }

    public JsonNode trackSearch(String track, String artist) {
        return trackSearch(track, artist, 1, 30);
    }

    public JsonNode trackGetCorrection(String artist, String track) {
        return get("track.getCorrection", Map.of("artist", artist, "track", track));
    }

    // ═══ USER ═══

    public JsonNode userGetInfo(String user) {
        return get("user.getInfo", Map.of("user", user));
    }

    public JsonNode userGetTopArtists(String user, String period, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("user", user);
        if (period != null) params.put("period", period);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("user.getTopArtists", params);
    }

    public JsonNode userGetTopArtists(String user, String period, int limit) {
        return userGetTopArtists(user, period, 1, limit);
    }

    public JsonNode userGetTopTracks(String user, String period, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("user", user);
        if (period != null) params.put("period", period);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("user.getTopTracks", params);
    }

    public JsonNode userGetTopTracks(String user, String period, int limit) {
        return userGetTopTracks(user, period, 1, limit);
    }

    public JsonNode userGetLovedTracks(String user, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("user", user);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("user.getLovedTracks", params);
    }

    public JsonNode userGetLovedTracks(String user, int limit) {
        return userGetLovedTracks(user, 1, limit);
    }

    public JsonNode userGetRecentTracks(String user, Boolean extended, Integer page, Integer limit, Long from, Long to) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("user", user);
        if (extended != null) params.put("extended", extended ? "1" : "0");
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        if (from != null) params.put("from", String.valueOf(from));
        if (to != null) params.put("to", String.valueOf(to));
        return get("user.getRecentTracks", params);
    }

    public JsonNode userGetRecentTracks(String user, int limit) {
        return userGetRecentTracks(user, true, 1, limit, null, null);
    }

    public JsonNode userGetTopAlbums(String user, String period, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("user", user);
        if (period != null) params.put("period", period);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("user.getTopAlbums", params);
    }

    public JsonNode userGetTopAlbums(String user, String period, int limit) {
        return userGetTopAlbums(user, period, 1, limit);
    }

    public JsonNode userGetTopTags(String user, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("user", user);
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("user.getTopTags", params);
    }

    public JsonNode userGetPersonalTags(String user, String tag, String taggingType, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("user", user);
        params.put("tag", tag);
        params.put("taggingtype", taggingType);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("user.getPersonalTags", params);
    }

    public JsonNode userGetFriends(String user, Boolean recenttracks, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("user", user);
        if (recenttracks != null) params.put("recenttracks", recenttracks ? "1" : "0");
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("user.getFriends", params);
    }

    // ═══ TAG ═══

    public JsonNode tagGetInfo(String tag) {
        return get("tag.getInfo", Map.of("tag", tag));
    }

    public JsonNode tagGetSimilar(String tag) {
        return get("tag.getSimilar", Map.of("tag", tag));
    }

    public JsonNode tagGetTopAlbums(String tag, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("tag", tag);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("tag.getTopAlbums", params);
    }

    public JsonNode tagGetTopAlbums(String tag, int limit) {
        return tagGetTopAlbums(tag, 1, limit);
    }

    public JsonNode tagGetTopArtists(String tag, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("tag", tag);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("tag.getTopArtists", params);
    }

    public JsonNode tagGetTopArtists(String tag, int limit) {
        return tagGetTopArtists(tag, 1, limit);
    }

    public JsonNode tagGetTopTracks(String tag, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("tag", tag);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("tag.getTopTracks", params);
    }

    public JsonNode tagGetTopTracks(String tag, int limit) {
        return tagGetTopTracks(tag, 1, limit);
    }

    public JsonNode tagGetTopTags(Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("tag.getTopTags", params);
    }

    // ═══ CHART ═══

    public JsonNode chartGetTopArtists(Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("chart.getTopArtists", params);
    }

    public JsonNode chartGetTopTags(Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("chart.getTopTags", params);
    }

    public JsonNode chartGetTopTracks(Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("chart.getTopTracks", params);
    }

    // ═══ GEO ═══

    public JsonNode geoGetTopArtists(String country, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("country", country);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("geo.getTopArtists", params);
    }

    public JsonNode geoGetTopArtists(String country, int limit) {
        return geoGetTopArtists(country, 1, limit);
    }

    public JsonNode geoGetTopTracks(String country, String location, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("country", country);
        if (location != null) params.put("location", location);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("geo.getTopTracks", params);
    }

    public JsonNode geoGetTopTracks(String country, int limit) {
        return geoGetTopTracks(country, null, 1, limit);
    }

    // ═══ LIBRARY ═══

    public JsonNode libraryGetArtists(String user, Integer page, Integer limit) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("user", user);
        if (page != null) params.put("page", String.valueOf(page));
        if (limit != null) params.put("limit", String.valueOf(limit));
        return get("library.getArtists", params);
    }

    public JsonNode libraryGetArtists(String user, int limit) {
        return libraryGetArtists(user, 1, limit);
    }
}
