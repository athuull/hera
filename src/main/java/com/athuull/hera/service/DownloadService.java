package com.athuull.hera.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.athuull.hera.client.DowntifyClient;
import com.athuull.hera.config.DowntifyConfig;
import com.athuull.hera.model.AppSettings;
import com.athuull.hera.model.DownloadResult;
import com.athuull.hera.model.Track;
import com.athuull.hera.ws.ProgressWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    private final DowntifyClient downtifyClient;
    private final DowntifyConfig config;
    private final SettingsService settingsService;
    private final DeduplicationService dedupService;
    private final FormatCleanupService formatCleanupService;
    private final ProgressWebSocketHandler progressHandler;

    @Autowired
    public DownloadService(DowntifyClient downtifyClient,
                           DowntifyConfig config,
                           SettingsService settingsService,
                           DeduplicationService dedupService,
                           FormatCleanupService formatCleanupService,
                           ProgressWebSocketHandler progressHandler) {
        this.downtifyClient = downtifyClient;
        this.config = config;
        this.settingsService = settingsService;
        this.dedupService = dedupService;
        this.formatCleanupService = formatCleanupService;
        this.progressHandler = progressHandler;
    }

    public void configureDowntify() {
        try {
            AppSettings s = settingsService.getSettings();
            Map<String, Object> settings = new HashMap<>();
            settings.put("format", s.getFormat());
            settings.put("bitrate", s.getBitrate());
            settings.put("organize_by_artist", s.isOrganizeByArtist());
            settings.put("download_lyrics", s.isDownloadLyrics());
            settings.put("output", "{artists} - {title}.{output-ext}");

            downtifyClient.updateSettings(settings);
            log.info("Downtify settings pushed: format={}, bitrate={}, organize={}",
                    s.getFormat(), s.getBitrate(), s.isOrganizeByArtist());
        } catch (Exception e) {
            log.warn("Could not push settings to Downtify: {}", e.getMessage());
        }
    }

    public Optional<JsonNode> searchForTrack(Track track) {
        try {
            JsonNode results = downtifyClient.searchSongs(track.toSearchQuery());
            if (results != null && results.isArray() && !results.isEmpty()) {
                return Optional.of(results.get(0));
            }
        } catch (Exception e) {
            log.warn("Search failed for '{} - {}': {}", track.getArtist(), track.getTitle(), e.getMessage());
        }
        return Optional.empty();
    }

    public List<DownloadResult> downloadBatch(List<Track> tracks) {
        dedupService.refreshIndex();

        List<JsonNode> songsToDownload = new ArrayList<>();
        List<Track> matchedTracks = new ArrayList<>();
        int skipped = 0;
        int rejectedBadMatch = 0;

        for (Track track : tracks) {
            if (dedupService.alreadyDownloaded(track.getArtist(), track.getTitle())) {
                log.debug("Skipping (already downloaded): {} - {}", track.getArtist(), track.getTitle());
                skipped++;
                broadcastStatus(track, "skipped", "already downloaded");
                continue;
            }

            Optional<JsonNode> song = searchForTrack(track);
            if (song.isEmpty()) {
                log.warn("No YouTube Music match for: {} - {}", track.getArtist(), track.getTitle());
                broadcastStatus(track, "error", "no match found on youtube music");
                continue;
            }

            JsonNode matched = song.get();

            String matchedTitle = matched.path("name").asText("");
            String matchedArtist = matched.path("artists").isArray() && matched.path("artists").size() > 0
                    ? matched.path("artists").get(0).asText("")
                    : matched.path("artist").asText("");

            if (!isPlausibleMatch(track, matchedArtist, matchedTitle)) {
                log.warn("Rejecting implausible match for '{} - {}': search returned '{} - {}'",
                        track.getArtist(), track.getTitle(), matchedArtist, matchedTitle);
                rejectedBadMatch++;
                broadcastStatus(track, "error", "bad match rejected: " + matchedTitle);
                continue;
            }

            if (dedupService.alreadyDownloaded(matchedArtist, matchedTitle)) {
                log.debug("Skipping (resolved match already downloaded): {} - {}", matchedArtist, matchedTitle);
                skipped++;
                broadcastStatus(track, "skipped", "already downloaded (matched name)");
                continue;
            }

            songsToDownload.add(matched);
            matchedTracks.add(track);
        }

        log.info("Batch prepared: {} to download, {} skipped (dedup), {} bad matches rejected, {} not found",
                songsToDownload.size(), skipped, rejectedBadMatch,
                tracks.size() - songsToDownload.size() - skipped - rejectedBadMatch);

        if (songsToDownload.isEmpty()) {
            log.info("Nothing to download — all tracks already exist, weren't found, or had no valid match");
            return Collections.emptyList();
        }

        try {
            downtifyClient.clearQueue();
            log.info("Cleared Downtify queue");
        } catch (Exception e) {
            log.warn("Could not clear Downtify queue: {}", e.getMessage());
        }

        JsonNode batchResponse = downtifyClient.downloadBatch(songsToDownload);
        int expectedCount = batchResponse != null ? batchResponse.path("count").asInt(0) : 0;

        log.info("Batch download queued: {} jobs", expectedCount);
        List<DownloadResult> results = pollQueueUntilComplete(expectedCount, matchedTracks);

        try {
            List<String> converted = formatCleanupService.cleanupWebmFiles();
            if (!converted.isEmpty()) {
                log.info("Format cleanup: converted {} .webm → .mp3", converted.size());
            }
        } catch (Exception e) {
            log.warn("Format cleanup failed: {}", e.getMessage());
        }

        long succeeded = results.stream().filter(DownloadResult::isDone).count();
        long failed = results.stream().filter(DownloadResult::isError).count();
        try {
            progressHandler.broadcast(
                    "{\"type\":\"batch_complete\",\"downloaded\":" + succeeded +
                            ",\"failed\":" + failed + "}");
        } catch (Exception e) {
            log.debug("Could not broadcast batch completion: {}", e.getMessage());
        }

        return results;
    }

    private void broadcastStatus(Track track, String status, String message) {
        try {
            String json = String.format(
                    "{\"song\":{\"artist\":\"%s\",\"title\":\"%s\"},\"status\":\"%s\",\"message\":\"%s\"}",
                    escapeJson(track.getArtist()), escapeJson(track.getTitle()), status, escapeJson(message)
            );
            progressHandler.broadcast(json);
        } catch (Exception e) {
            log.debug("Failed to broadcast status: {}", e.getMessage());
        }
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\"", "\\\"");
    }

    private boolean isPlausibleMatch(Track requested, String matchedArtist, String matchedTitle) {
        if (matchedTitle == null || matchedTitle.isBlank()) return false;

        String reqTitle = requested.getTitle().toLowerCase().trim();
        String gotTitle = matchedTitle.toLowerCase().trim();
        String reqArtist = requested.getArtist().toLowerCase().trim();
        String gotArtist = matchedArtist == null ? "" : matchedArtist.toLowerCase().trim();

        boolean titleOverlaps = gotTitle.contains(reqTitle) || reqTitle.contains(gotTitle);
        boolean artistOverlaps = gotArtist.isBlank()
                || gotArtist.contains(reqArtist)
                || reqArtist.contains(gotArtist);

        return titleOverlaps && artistOverlaps;
    }

    private List<DownloadResult> pollQueueUntilComplete(int expectedCount, List<Track> tracks) {
        List<DownloadResult> results = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        while (results.size() < expectedCount) {
            if (System.currentTimeMillis() - startTime > config.getPollTimeoutMs()) {
                log.warn("Poll timeout reached with {}/{} jobs completed", results.size(), expectedCount);
                break;
            }

            try {
                Thread.sleep(config.getPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            JsonNode queue = downtifyClient.getQueue();
            if (queue == null || !queue.isArray()) continue;

            int completedInThisPoll = 0;
            List<DownloadResult> newResults = new ArrayList<>();

            for (JsonNode job : queue) {
                String status = job.path("status").asText("unknown");

                if ("done".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status)) {
                    completedInThisPoll++;
                    String filename = job.path("filename").asText(null);
                    newResults.add(DownloadResult.builder()
                            .filename(filename)
                            .status("done")
                            .build());
                } else if ("error".equalsIgnoreCase(status)) {
                    completedInThisPoll++;
                    String error = job.has("error") ? job.get("error").asText() : "Unknown error";
                    newResults.add(DownloadResult.builder()
                            .status("error")
                            .errorMessage(error)
                            .build());
                }
            }

            if (completedInThisPoll > results.size()) {
                results = newResults;
                log.info("Progress: {}/{} jobs completed", results.size(), expectedCount);
            }
        }

        for (int i = 0; i < results.size() && i < tracks.size(); i++) {
            results.get(i).setTrack(tracks.get(i));
        }

        return results;
    }

    public DownloadResult downloadSingleByUrl(String url) {
        try {
            String filename = downtifyClient.downloadSingle(url);
            return DownloadResult.builder().filename(filename).status("done").build();
        } catch (Exception e) {
            log.error("Single download failed for '{}': {}", url, e.getMessage());
            return DownloadResult.builder().status("error").errorMessage(e.getMessage()).build();
        }
    }

    public List<String> listDownloadedFiles() {
        return downtifyClient.listFiles();
    }
}