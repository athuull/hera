package com.athuull.hera.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.locks.ReentrantLock;

@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    private final DowntifyClient downtifyClient;
    private final DowntifyConfig config;
    private final SettingsService settingsService;
    private final DeduplicationService dedupService;
    private final FormatCleanupService formatCleanupService;
    private final ProgressWebSocketHandler progressHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock downloadLock = new ReentrantLock();

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
            log.info("Downtify settings pushed: format={}, bitrate={}, organize={}, lyrics={}",
                    s.getFormat(), s.getBitrate(), s.isOrganizeByArtist(), s.isDownloadLyrics());
        } catch (Exception e) {
            log.warn("Could not push settings to Downtify: {}", e.getMessage());
        }
    }

    public Optional<JsonNode> searchForTrack(Track track) {
        try {
            JsonNode results = downtifyClient.searchSongs(track.toSearchQuery());
            if (results == null || !results.isArray() || results.isEmpty()) {
                return Optional.empty();
            }

            for (JsonNode candidate : results) {
                String cTitle  = extractCandidateTitle(candidate);
                String cArtist = extractCandidateArtist(candidate);
                if (isPlausibleMatch(track, cArtist, cTitle)) {
                    return Optional.of(candidate);
                }
            }

            log.warn("No plausible match in {} results for '{} - {}'",
                    results.size(), track.getArtist(), track.getTitle());
        } catch (Exception e) {
            log.warn("Search failed for '{} - {}': {}", track.getArtist(), track.getTitle(), e.getMessage());
        }
        return Optional.empty();
    }

    public List<DownloadResult> downloadBatch(List<Track> tracks) {
        if (!downloadLock.tryLock()) {
            log.warn("Another download batch is already active. Skipping request.");
            broadcastStatus(new Track("System", "Batch", null, null), "error", "Another download batch is already active");
            return Collections.emptyList();
        }

        try {
            dedupService.refreshIndex();
            broadcastBatchStart(tracks != null ? tracks.size() : 0);

            List<JsonNode> songsToDownload = new ArrayList<>();
            List<Track> matchedTracks = new ArrayList<>();
            List<DownloadResult> results = new ArrayList<>();
            int skipped = 0;
            int notFound = 0;

            for (Track track : tracks) {
                if (dedupService.alreadyDownloaded(track.getArtist(), track.getTitle())) {
                    log.debug("Skipping (already downloaded): {} - {}", track.getArtist(), track.getTitle());
                    skipped++;
                    broadcastStatus(track, "skipped", "already downloaded");
                    results.add(DownloadResult.builder()
                            .track(track)
                            .status("skipped")
                            .errorMessage("already downloaded")
                            .build());
                    continue;
                }

                Optional<JsonNode> song = searchForTrack(track);
                if (song.isEmpty()) {
                    log.warn("No plausible YouTube Music match for: {} - {}", track.getArtist(), track.getTitle());
                    broadcastStatus(track, "error", "no match found on youtube music");
                    notFound++;
                    results.add(DownloadResult.builder()
                            .track(track)
                            .status("error")
                            .errorMessage("no match found on youtube music")
                            .build());
                    continue;
                }

                JsonNode matched = song.get();

                String matchedTitle  = extractCandidateTitle(matched);
                String matchedArtist = extractCandidateArtist(matched);

                if (dedupService.alreadyDownloaded(matchedArtist, matchedTitle)) {
                    log.debug("Skipping (resolved match already downloaded): {} - {}", matchedArtist, matchedTitle);
                    skipped++;
                    broadcastStatus(track, "skipped", "already downloaded (matched name)");
                    results.add(DownloadResult.builder()
                            .track(track)
                            .status("skipped")
                            .errorMessage("already downloaded (matched name)")
                            .build());
                    continue;
                }

                songsToDownload.add(matched);
                matchedTracks.add(track);
            }

            log.info("Batch prepared: {} to download, {} skipped (dedup), {} not found on youtube music",
                    songsToDownload.size(), skipped, notFound);

            int totalTracks = tracks != null ? tracks.size() : 0;

            if (songsToDownload.isEmpty()) {
                log.info("Nothing to download — all tracks already exist, weren't found, or had no valid match");
                broadcastBatchComplete(totalTracks, 0, notFound, skipped);
                return results;
            }

            try {
                downtifyClient.clearQueue();
                log.info("Cleared Downtify queue");
            } catch (Exception e) {
                log.warn("Could not clear Downtify queue: {}", e.getMessage());
            }

            int preCompleted = skipped + notFound;
            List<DownloadResult> downloadResults;

            try {
                JsonNode batchResponse = downtifyClient.downloadBatch(songsToDownload);
                int expectedCount = (batchResponse != null && batchResponse.has("count") && batchResponse.path("count").asInt() > 0)
                        ? batchResponse.path("count").asInt()
                        : songsToDownload.size();

                log.info("Batch download queued: {} jobs", expectedCount);
                downloadResults = pollQueueUntilComplete(expectedCount, matchedTracks, songsToDownload, totalTracks, preCompleted);
            } catch (Exception e) {
                log.error("Failed to queue or execute batch download with Downtify: {}", e.getMessage(), e);
                downloadResults = new ArrayList<>();
                for (Track track : matchedTracks) {
                    broadcastStatus(track, "error", "Failed to queue download: " + e.getMessage());
                    downloadResults.add(DownloadResult.builder()
                            .track(track)
                            .status("error")
                            .errorMessage("Failed to queue download: " + e.getMessage())
                            .build());
                }
            }

            results.addAll(downloadResults);

            try {
                List<String> converted = formatCleanupService.cleanupWebmFiles();
                if (!converted.isEmpty()) {
                    log.info("Format cleanup: converted {} .webm → .mp3", converted.size());
                }
            } catch (Exception e) {
                log.warn("Format cleanup failed: {}", e.getMessage());
            }

            // Refresh index after download batch finishes
            dedupService.refreshIndex();

            long succeeded = results.stream().filter(DownloadResult::isDone).count();
            long failed = results.stream().filter(DownloadResult::isError).count();
            broadcastBatchComplete(totalTracks, succeeded, failed, skipped);

            return results;
        } finally {
            downloadLock.unlock();
        }
    }

    private void broadcastStatus(Track track, String status, String message) {
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

    private void broadcastBatchStart(int total) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "batch_start");
            payload.put("total", total);
            progressHandler.broadcast(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("Failed to broadcast batch start: {}", e.getMessage());
        }
    }

    private void broadcastBatchProgress(int completed, int total) {
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

    private void broadcastBatchComplete(int total, long succeeded, long failed, int skipped) {
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

    public boolean isPlausibleMatch(Track requested, String matchedArtist, String matchedTitle) {
        if (matchedTitle == null || matchedTitle.isBlank()) return false;

        // Titles: only strip feat credits and YouTube noise — (Skit), (Remix), (Live) etc. must survive
        // so they can distinguish fundamentally different tracks.
        String reqTitle  = cleanTitle(requested.getTitle());
        String gotTitle  = cleanTitle(matchedTitle);

        // Artists: strip everything aggressively — feat lists, collaboration credits, etc.
        String reqArtist = cleanForComparison(requested.getArtist());
        String gotArtist = matchedArtist == null ? "" : cleanForComparison(matchedArtist);

        // Title: require equality after cleaning.
        // contains() was too loose — "say it (skit)".contains("say it") = true,
        // which caused "Say It (Skit)" to be accepted when "Say It" was requested.
        boolean titleMatches   = gotTitle.equals(reqTitle);

        // Artist: matched artist must contain the full requested name (safe direction only).
        // reqArtist.contains(gotArtist) was too loose — "tory lanez".contains("lanez") = true,
        // which caused "Lanez" (different artist) to be accepted for "Tory Lanez".
        boolean artistOverlaps = false;
        if (gotArtist.isBlank() || gotArtist.contains(reqArtist)) {
            artistOverlaps = true;
        } else if (requested.getArtist() != null && (requested.getArtist().contains(";") || requested.getArtist().contains("/"))) {
            // Semicolon/slash-separated artist credits (e.g. "Tory Lanez; Tee" or "BoyWithUke; blackbear")
            String[] parts = requested.getArtist().split("[;/]");
            for (String part : parts) {
                String cleanPart = cleanForComparison(part);
                if (!cleanPart.isBlank() && gotArtist.contains(cleanPart)) {
                    artistOverlaps = true;
                    break;
                }
            }
        }

        return titleMatches && artistOverlaps;
    }

    /**
     * Cleans a track TITLE for plausibility comparison.
     * Only strips feat/ft credits and known YouTube Music noise annotations (Official Video, Audio, etc.).
     * Preserves meaningful parenthetical identifiers like (Skit), (Remix), (Live), (Acoustic), etc.
     * so that "Say It (Skit)" is NOT accepted as a match for "Say It".
     */
    String cleanTitle(String input) {
        if (input == null) return "";
        String s = input.toLowerCase();
        // Strip feat/ft credits inside parens or brackets only
        s = s.replaceAll("\\(\\s*f(?:eat|t)\\.?[^)]*\\)", "");
        s = s.replaceAll("\\[\\s*f(?:eat|t)\\.?[^\\]]*\\]", "");
        // Strip YouTube Music noise annotations that don't change the track's identity
        s = s.replaceAll("\\(\\s*(?:official\\s+(?:video|audio|music\\s+video)|music\\s+video|audio|lyric(?:s|\\s+video)?|visuali[zs]er|hd|hq)\\s*\\)", "");
        // Strip bare feat./ft. that wasn't already in parens
        s = s.replaceAll("\\s+f(?:eat|t)\\..*", "");
        return s.trim();
    }

    /**
     * Cleans an ARTIST string for plausibility comparison.
     * Aggressively strips all parenthetical content (feat lists, collaboration credits, etc.)
     * because artist display names have no meaningful parenthetical distinctions.
     */
    String cleanForComparison(String input) {
        if (input == null) return "";
        String s = input.toLowerCase();
        // Strip all parenthesised and bracketed annotations
        s = s.replaceAll("\\([^)]*\\)", "");
        s = s.replaceAll("\\[[^\\]]*\\]", "");
        // Strip everything after a bare feat. / ft. that wasn't already in parens
        s = s.replaceAll("\\s+feat\\..*", "");
        s = s.replaceAll("\\s+ft\\..*", "");
        return s.trim();
    }

    /**
     * Extracts a displayable artist name from a single element of ytmusicapi's 'artists' array.
     * The array may contain plain strings ("Tory Lanez") or objects ({"name": "Tory Lanez", "id": "UC..."}).
     * Calling asText() on an object node returns empty string in Jackson, so we must check the node type.
     */
    String extractArtistName(JsonNode artistNode) {
        if (artistNode == null) return "";
        // Object node: {"name": "Tory Lanez", "id": "UCxxx"}
        if (artistNode.isObject()) return artistNode.path("name").asText("");
        // Plain text node: "Tory Lanez"
        return artistNode.asText("");
    }

    /**
     * Extracts title from candidate node, checking 'name' and 'title'.
     */
    String extractCandidateTitle(JsonNode candidate) {
        if (candidate == null) return "";
        if (candidate.hasNonNull("name")) {
            return candidate.get("name").asText("");
        }
        return candidate.path("title").asText("");
    }

    /**
     * Extracts full artist representation from candidate node.
     * Combines all artists if 'artists' array is present, else falls back to 'artist'.
     */
    String extractCandidateArtist(JsonNode candidate) {
        if (candidate == null) return "";
        JsonNode artistsNode = candidate.path("artists");
        if (artistsNode.isArray() && !artistsNode.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (JsonNode a : artistsNode) {
                String name = extractArtistName(a);
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
            if (!names.isEmpty()) {
                return String.join(" & ", names);
            }
        }
        return candidate.path("artist").asText("");
    }

    private List<DownloadResult> pollQueueUntilComplete(int expectedCount, List<Track> tracks, List<JsonNode> songNodes,
                                                        int totalBatchSize, int preCompleted) {
        List<DownloadResult> results = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        Map<String, Track> trackLookup = new HashMap<>();
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            trackLookup.put(t.dedupeKey(), t);
            if (i < songNodes.size()) {
                JsonNode sn = songNodes.get(i);
                String mTitle  = extractCandidateTitle(sn);
                String mArtist = extractCandidateArtist(sn);
                trackLookup.put(new Track(mArtist, mTitle, null, null).dedupeKey(), t);
                if (sn.has("id")) trackLookup.put(sn.get("id").asText(), t);
                if (sn.has("videoId")) trackLookup.put(sn.get("videoId").asText(), t);
            }
        }

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
                    Track matchedTrack = resolveTrackForJob(job, filename, trackLookup);
                    newResults.add(DownloadResult.builder()
                            .filename(filename)
                            .status("done")
                            .track(matchedTrack)
                            .build());
                } else if ("error".equalsIgnoreCase(status)) {
                    completedInThisPoll++;
                    String error = job.has("error") ? job.get("error").asText() : "Unknown error";
                    String filename = job.path("filename").asText(null);
                    Track matchedTrack = resolveTrackForJob(job, filename, trackLookup);
                    newResults.add(DownloadResult.builder()
                            .status("error")
                            .errorMessage(error)
                            .track(matchedTrack)
                            .build());
                }
            }

            if (completedInThisPoll > results.size()) {
                results = newResults;
                log.info("Progress: {}/{} jobs completed", results.size(), expectedCount);
                int progressTotal = totalBatchSize > 0 ? totalBatchSize : expectedCount;
                broadcastBatchProgress(preCompleted + results.size(), progressTotal);
            }
        }

        // Fallback matching for any unlinked results
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).getTrack() == null && i < tracks.size()) {
                results.get(i).setTrack(tracks.get(i));
            }
        }

        // Handle any tracks that timed out / did not finish in queue
        if (results.size() < tracks.size()) {
            Set<Track> resolvedTracks = new HashSet<>();
            for (DownloadResult dr : results) {
                if (dr.getTrack() != null) {
                    resolvedTracks.add(dr.getTrack());
                }
            }
            for (Track t : tracks) {
                if (!resolvedTracks.contains(t)) {
                    log.warn("Download timed out or failed to complete for track: {} - {}", t.getArtist(), t.getTitle());
                    broadcastStatus(t, "error", "Download timed out");
                    results.add(DownloadResult.builder()
                            .track(t)
                            .status("error")
                            .errorMessage("Download timed out")
                            .build());
                }
            }
        }

        return results;
    }

    private Track resolveTrackForJob(JsonNode job, String filename, Map<String, Track> lookup) {
        if (filename != null && !filename.isBlank()) {
            String key = dedupService.normalizeFilename(filename);
            if (lookup.containsKey(key)) return lookup.get(key);
        }
        if (job.has("id") && lookup.containsKey(job.get("id").asText())) {
            return lookup.get(job.get("id").asText());
        }
        if (job.has("song_id") && lookup.containsKey(job.get("song_id").asText())) {
            return lookup.get(job.get("song_id").asText());
        }
        return null;
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