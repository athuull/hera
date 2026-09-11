package com.athuull.hera.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.athuull.hera.config.DowntifyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DowntifyClient {

    private final RestTemplate restTemplate;
    private final DowntifyConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    private String url(String path) {
        return config.getBaseUrl() + path;
    }

    private String url(String path, String key, String val) {
        return UriComponentsBuilder.fromHttpUrl(url(path)).queryParam(key, val).build().toUriString();
    }

    public JsonNode searchSongs(String query) {
        return restTemplate.getForEntity(url("/api/songs/search", "query", query), JsonNode.class).getBody();
    }

    public JsonNode resolveSpotifyUrl(String spotifyUrl) {
        return restTemplate.getForEntity(url("/api/song/url", "url", spotifyUrl), JsonNode.class).getBody();
    }

    public String downloadSingle(String url) {
        return restTemplate.postForEntity(url("/api/download/url", "url", url), null, String.class).getBody();
    }

    public JsonNode downloadBatch(List<JsonNode> songs, String playlistUrl, boolean generateM3u) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ObjectNode body = mapper.createObjectNode();
        ArrayNode songsArray = body.putArray("songs");
        songs.forEach(songsArray::add);
        if (playlistUrl != null) body.put("playlist_url", playlistUrl);
        body.put("generate_m3u", generateM3u);

        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);
        return restTemplate.postForEntity(url("/api/download/batch"), entity, JsonNode.class).getBody();
    }

    public JsonNode downloadBatch(List<JsonNode> songs) {
        return downloadBatch(songs, null, false);
    }

    public JsonNode getQueue() {
        return restTemplate.getForEntity(url("/api/queue"), JsonNode.class).getBody();
    }

    public void clearQueue() {
        restTemplate.delete(url("/api/queue"));
    }

    public boolean removeQueueItem(String songId) {
        ResponseEntity<JsonNode> resp = restTemplate.exchange(url("/api/queue/item", "song_id", songId), HttpMethod.DELETE, null, JsonNode.class);
        return resp.getBody() != null && resp.getBody().path("removed").asBoolean(false);
    }

    public JsonNode getSettings() {
        return restTemplate.getForEntity(url("/api/settings"), JsonNode.class).getBody();
    }

    public JsonNode updateSettings(Map<String, Object> settings) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(url("/api/settings/update"), new HttpEntity<>(settings, headers), JsonNode.class).getBody();
    }

    public List<String> listFiles() {
        JsonNode body = restTemplate.getForEntity(url("/list"), JsonNode.class).getBody();
        List<String> files = new ArrayList<>();
        if (body != null && body.isArray()) body.forEach(n -> files.add(n.asText()));
        return files;
    }

    public boolean deleteFile(String relativePath) {
        ResponseEntity<JsonNode> resp = restTemplate.exchange(url("/delete", "file", relativePath), HttpMethod.DELETE, null, JsonNode.class);
        return resp.getBody() != null && resp.getBody().path("deleted").asBoolean(false);
    }

    public byte[] getCoverArt(String relativePath) {
        return restTemplate.getForEntity(url("/cover", "file", relativePath), byte[].class).getBody();
    }

    public JsonNode getMonitoredPlaylists() {
        return restTemplate.getForEntity(url("/api/monitor/playlists"), JsonNode.class).getBody();
    }

    public JsonNode monitorPlaylist(String playlistUrl, int intervalMinutes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ObjectNode body = mapper.createObjectNode().put("url", playlistUrl).put("interval_minutes", intervalMinutes);
        return restTemplate.postForEntity(url("/api/monitor/playlists"), new HttpEntity<>(body.toString(), headers), JsonNode.class).getBody();
    }

    public JsonNode checkPlaylist(String playlistId) {
        return restTemplate.postForEntity(url("/api/monitor/playlists/" + playlistId + "/check"), null, JsonNode.class).getBody();
    }

    public boolean stopMonitoringPlaylist(String playlistId) {
        ResponseEntity<JsonNode> resp = restTemplate.exchange(url("/api/monitor/playlists/" + playlistId), HttpMethod.DELETE, null, JsonNode.class);
        return resp.getBody() != null && resp.getBody().path("deleted").asBoolean(false);
    }

    public String getVersion() {
        return restTemplate.getForEntity(url("/api/version"), String.class).getBody();
    }
}
