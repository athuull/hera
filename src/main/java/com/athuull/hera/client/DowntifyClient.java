package com.athuull.hera.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.athuull.hera.config.DowntifyConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DowntifyClient {

    private final RestTemplate restTemplate;
    private final DowntifyConfig config;

    @Autowired
    public DowntifyClient(RestTemplate restTemplate, DowntifyConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    public JsonNode searchSongs(String query) {
        String url = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/api/songs/search")
                .queryParam("query", query)
                .build().toUriString();
        return restTemplate.getForEntity(url, JsonNode.class).getBody();
    }

    public JsonNode resolveSpotifyUrl(String spotifyUrl) {
        String url = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/api/song/url")
                .queryParam("url", spotifyUrl)
                .build().toUriString();
        return restTemplate.getForEntity(url, JsonNode.class).getBody();
    }

    public String downloadSingle(String url) {
        String fullUrl = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/api/download/url")
                .queryParam("url", url)
                .build().toUriString();
        ResponseEntity<String> response = restTemplate.postForEntity(fullUrl, null, String.class);
        return response.getBody();
    }

    public JsonNode downloadBatch(List<JsonNode> songs, String playlistUrl, boolean generateM3u) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        ArrayNode songsArray = body.putArray("songs");
        songs.forEach(songsArray::add);

        if (playlistUrl != null) body.put("playlist_url", playlistUrl);
        body.put("generate_m3u", generateM3u);

        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                config.getBaseUrl() + "/api/download/batch", entity, JsonNode.class);
        return response.getBody();
    }

    public JsonNode downloadBatch(List<JsonNode> songs) {
        return downloadBatch(songs, null, false);
    }

    public JsonNode getQueue() {
        return restTemplate.getForEntity(
                config.getBaseUrl() + "/api/queue", JsonNode.class).getBody();
    }

    public void clearQueue() {
        restTemplate.delete(config.getBaseUrl() + "/api/queue");
    }

    public boolean removeQueueItem(String songId) {
        String url = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/api/queue/item")
                .queryParam("song_id", songId)
                .build().toUriString();
        ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.DELETE, null, JsonNode.class);
        JsonNode body = response.getBody();
        return body != null && body.path("removed").asBoolean(false);
    }

    public JsonNode getSettings() {
        return restTemplate.getForEntity(
                config.getBaseUrl() + "/api/settings", JsonNode.class).getBody();
    }

    public JsonNode updateSettings(Map<String, Object> settings) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(settings, headers);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                config.getBaseUrl() + "/api/settings/update", entity, JsonNode.class);
        return response.getBody();
    }

    public List<String> listFiles() {
        JsonNode body = restTemplate.getForEntity(
                config.getBaseUrl() + "/list", JsonNode.class).getBody();
        List<String> files = new ArrayList<>();
        if (body != null && body.isArray()) {
            body.forEach(node -> files.add(node.asText()));
        }
        return files;
    }

    public boolean deleteFile(String relativePath) {
        String url = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/delete")
                .queryParam("file", relativePath)
                .build().toUriString();
        ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.DELETE, null, JsonNode.class);
        JsonNode body = response.getBody();
        return body != null && body.path("deleted").asBoolean(false);
    }

    public byte[] getCoverArt(String relativePath) {
        String url = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/cover")
                .queryParam("file", relativePath)
                .build().toUriString();
        ResponseEntity<byte[]> response = restTemplate.getForEntity(url, byte[].class);
        return response.getBody();
    }

    public JsonNode getMonitoredPlaylists() {
        return restTemplate.getForEntity(
                config.getBaseUrl() + "/api/monitor/playlists", JsonNode.class).getBody();
    }

    public JsonNode monitorPlaylist(String playlistUrl, int intervalMinutes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.put("url", playlistUrl);
        body.put("interval_minutes", intervalMinutes);

        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);
        return restTemplate.postForEntity(
                config.getBaseUrl() + "/api/monitor/playlists", entity, JsonNode.class).getBody();
    }

    public JsonNode checkPlaylist(String playlistId) {
        return restTemplate.postForEntity(
                config.getBaseUrl() + "/api/monitor/playlists/" + playlistId + "/check",
                null, JsonNode.class).getBody();
    }

    public boolean stopMonitoringPlaylist(String playlistId) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                config.getBaseUrl() + "/api/monitor/playlists/" + playlistId,
                HttpMethod.DELETE, null, JsonNode.class);
        JsonNode body = response.getBody();
        return body != null && body.path("deleted").asBoolean(false);
    }

    public String getVersion() {
        return restTemplate.getForEntity(
                config.getBaseUrl() + "/api/version", String.class).getBody();
    }
}
