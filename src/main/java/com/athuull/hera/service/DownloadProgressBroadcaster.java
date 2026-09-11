package com.athuull.hera.service;

import com.athuull.hera.model.Track;
import com.athuull.hera.ws.ProgressWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DownloadProgressBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(DownloadProgressBroadcaster.class);

    private final ProgressWebSocketHandler progressHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DownloadProgressBroadcaster(ProgressWebSocketHandler progressHandler) {
        this.progressHandler = progressHandler;
    }

    public void broadcastStatus(Track track, String status, String message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            Map<String, String> song = new LinkedHashMap<>();
            song.put("artist", track != null ? track.getArtist() : "");
            song.put("title", track != null ? track.getTitle() : "");
            payload.put("song", song);
            payload.put("status", status);
            payload.put("message", message);
            progressHandler.broadcast(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("Failed to broadcast status: {}", e.getMessage());
        }
    }

    public void broadcastBatchStart(int total) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "batch_start");
            payload.put("total", total);
            progressHandler.broadcast(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("Failed to broadcast batch start: {}", e.getMessage());
        }
    }

    public void broadcastBatchProgress(int completed, int total) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "batch_progress");
            payload.put("completed", completed);
            payload.put("total", total);
            progressHandler.broadcast(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("Failed to broadcast batch progress: {}", e.getMessage());
        }
    }

    public void broadcastBatchComplete(int total, long succeeded, long failed, int skipped) {
        try {
            Map<String, Object> completeMsg = new LinkedHashMap<>();
            completeMsg.put("type", "batch_complete");
            completeMsg.put("total", total);
            completeMsg.put("downloaded", succeeded);
            completeMsg.put("failed", failed);
            completeMsg.put("skipped", skipped);
            progressHandler.broadcast(objectMapper.writeValueAsString(completeMsg));
        } catch (Exception e) {
            log.debug("Could not broadcast batch completion: {}", e.getMessage());
        }
    }
}
