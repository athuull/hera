package com.athuull.hera.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.athuull.hera.client.DowntifyClient;
import com.athuull.hera.config.DowntifyConfig;
import com.athuull.hera.model.AppSettings;
import com.athuull.hera.model.DownloadResult;
import com.athuull.hera.model.HistoryEntry;
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
    private final HistoryService historyService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock downloadLock = new ReentrantLock();

    @Autowired
    public DownloadService(DowntifyClient downtifyClient,
                           DowntifyConfig config,
                           SettingsService settingsService,
                           DeduplicationService dedupService,
                           FormatCleanupService formatCleanupService,
                           ProgressWebSocketHandler progressHandler,
                           HistoryService historyService) {
        this.downtifyClient = downtifyClient;
        this.config = config;
        this.settingsService = settingsService;
        this.dedupService = dedupService;
        this.formatCleanupService = formatCleanupService;
        this.progressHandler = progressHandler;
        this.historyService = historyService;
    }

    public void configureDowntify() {
        try {
            AppSettings s = settingsService.getSettings();
            Map<String, Object> settings = new HashMap<>();
            settings.put("format", s.getFormat());
            settings.put("bitrate", s.getBitrate());
            settings.put("organize_by_artist", s.isOrganizeByArtist());
            settings.put("download_lyrics", s.isDownloadLyrics());
            settings.put("download_cover_art", s.isDownloadCoverArt());
            settings.put("cover_resolution", s.getCoverResolution() > 0 ? s.getCoverResolution() : 600);
            settings.put("output", "{artists} - {title}.{output-ext}");

            downtifyClient.updateSettings(settings);
            log.info("Downtify settings pushed: format={}, bitrate={}, organize={}, lyrics={}, coverArt={}, coverResolution={}",
                    s.getFormat(), s.getBitrate(), s.isOrganizeByArtist(), s.isDownloadLyrics(), s.isDownloadCoverArt(), s.getCoverResolution());
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

            configureDowntify();

            try {
                downtifyClient.clearQueue();
                log.info("Cleared Downtify queue");
            } catch (Exception e) {
                log.warn("Could not clear queue before batch: {}", e.getMessage());
            }

            int preCompleted = skipped + notFound;
            try {
                downtifyClient.downloadBatch(songsToDownload);
                log.info("Batch download queued: {} jobs", songsToDownload.size());
            } catch (Exception e) {
                log.error("Failed to queue batch download: {}", e.getMessage());
                for (Track track : matchedTracks) {
                    broadcastStatus(track, "error", "Failed to queue download: " + e.getMessage());
                    results.add(DownloadResult.builder()
                            .track(track)
                            .status("error")
                            .errorMessage("Failed to queue download: " + e.getMessage())
                            .build());
                }
                long succeeded = results.stream().filter(DownloadResult::isDone).count();
                long failed = results.stream().filter(DownloadResult::isError).count();
                broadcastBatchComplete(totalTracks, succeeded, failed, skipped);
                return results;
            }

            List<DownloadResult> polledResults = pollQueueUntilComplete(
                    songsToDownload.size(), matchedTracks, songsToDownload, totalTracks, preCompleted);
            results.addAll(polledResults);

            try {
                List<String> converted = formatCleanupService.cleanupWebmFiles();
                if (!converted.isEmpty()) {
                    log.info("Converted {} dangling .webm files to configured format", converted.size());
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

    private void broadcast(Object payload) {
        if (progressHandler == null) return;
        try {
            progressHandler.broadcast(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("Failed to broadcast progress: {}", e.getMessage());
        }
    }

    private void broadcastStatus(Track track, String status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, String> song = new LinkedHashMap<>();
        song.put("artist", track != null && track.getArtist() != null ? track.getArtist() : "");
        song.put("title", track != null && track.getTitle() != null ? track.getTitle() : "");
        payload.put("song", song);
        payload.put("status", status);
        payload.put("message", message != null ? message : "");
        broadcast(payload);
    }

    private void broadcastBatchStart(int total) {
        broadcast(Map.of("type", "batch_start", "total", total));
    }

    private void broadcastBatchProgress(int completed, int total) {
        broadcast(Map.of("type", "batch_progress", "completed", completed, "total", total));
    }

    private void broadcastBatchComplete(int total, long succeeded, long failed, int skipped) {
        broadcast(Map.of(
                "type", "batch_complete",
                "total", total,
                "downloaded", succeeded,
                "failed", failed,
                "skipped", skipped
        ));
    }

    public boolean isPlausibleMatch(Track requested, String matchedArtist, String matchedTitle) {
        if (matchedTitle == null || matchedTitle.isBlank()) return false;

        String reqTitle = cleanTitle(requested.getTitle());
        String gotTitle = cleanTitle(matchedTitle);

        String reqArtist = cleanForComparison(requested.getArtist());
        String gotArtist = matchedArtist == null ? "" : cleanForComparison(matchedArtist);

        boolean titleMatches = gotTitle.equals(reqTitle);
        boolean artistOverlaps = false;
        if (gotArtist.isBlank() || gotArtist.contains(reqArtist)) {
            artistOverlaps = true;
        } else if (requested.getArtist() != null && (requested.getArtist().contains(";") || requested.getArtist().contains("/"))) {
            for (String part : requested.getArtist().split("[;/]")) {
                String cleanPart = cleanForComparison(part);
                if (!cleanPart.isBlank() && gotArtist.contains(cleanPart)) {
                    artistOverlaps = true;
                    break;
                }
            }
        }

        return titleMatches && artistOverlaps;
    }

    String cleanTitle(String input) {
        if (input == null) return "";
        String s = input.toLowerCase();
        s = s.replaceAll("\\(\\s*f(?:eat|t)\\.?[^)]*\\)", "");
        s = s.replaceAll("\\[\\s*f(?:eat|t)\\.?[^\\]]*\\]", "");
        s = s.replaceAll("\\(\\s*(?:official\\s+(?:video|audio|music\\s+video)|music\\s+video|audio|lyric(?:s|\\s+video)?|visuali[zs]er|hd|hq)\\s*\\)", "");
        s = s.replaceAll("\\s+f(?:eat|t)\\..*", "");
        return s.trim();
    }

    String cleanForComparison(String input) {
        if (input == null) return "";
        String s = input.toLowerCase();
        s = s.replaceAll("\\([^)]*\\)", "");
        s = s.replaceAll("\\[[^\\]]*\\]", "");
        s = s.replaceAll("\\s+feat\\..*", "");
        s = s.replaceAll("\\s+ft\\..*", "");
        return s.trim();
    }

    String extractArtistName(JsonNode artistNode) {
        if (artistNode == null) return "";
        if (artistNode.isObject()) return artistNode.path("name").asText("");
        return artistNode.asText("");
    }

    String extractCandidateTitle(JsonNode candidate) {
        if (candidate == null) return "";
        if (candidate.hasNonNull("name")) return candidate.get("name").asText("");
        return candidate.path("title").asText("");
    }

    String extractCandidateArtist(JsonNode candidate) {
        if (candidate == null) return "";
        JsonNode artistsNode = candidate.path("artists");
        if (artistsNode.isArray() && !artistsNode.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (JsonNode a : artistsNode) {
                String name = extractArtistName(a);
                if (!name.isBlank()) names.add(name);
            }
            if (!names.isEmpty()) return String.join(" & ", names);
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
        return downloadUrlOrAlbum(url);
    }

    public DownloadResult downloadUrlOrAlbum(String url) {
        if (url == null || url.isBlank()) {
            return DownloadResult.builder().status("error").errorMessage("URL cannot be empty").build();
        }
        String cleanUrl = url.trim();
        try {
            configureDowntify();
            log.info("Processing URL download request: {}", cleanUrl);
            broadcast(Map.of(
                    "type", "log",
                    "message", "system: received download link -> " + cleanUrl
            ));

            boolean isYtMusicAlbum = cleanUrl.contains("music.youtube.com") && (cleanUrl.contains("/browse/") || cleanUrl.contains("album"));
            if (isYtMusicAlbum) {
                log.info("Triggering YouTube Music album download via /api/download/album for {}", cleanUrl);
                broadcast(Map.of(
                        "type", "log",
                        "message", "system: downloading YouTube Music album..."
                ));
                JsonNode albumResult = downtifyClient.downloadAlbum(cleanUrl);
                int count = albumResult != null && albumResult.isObject() ? albumResult.size() : 1;
                broadcast(Map.of(
                        "type", "log",
                        "message", "system: album download completed (" + count + " tracks)"
                ));
                dedupService.refreshIndex();
                historyService.record(HistoryEntry.builder()
                        .artist("").title("")
                        .filename(cleanUrl)
                        .source("URL_IMPORT")
                        .reason("youtube music album (" + count + " tracks)")
                        .status("SUCCESS")
                        .build());
                return DownloadResult.builder().status("done").filename(cleanUrl).build();
            }

            try {
                JsonNode resolved = downtifyClient.resolveUrl(cleanUrl);
                if (resolved != null && resolved.isArray() && !resolved.isEmpty()) {
                    List<JsonNode> songList = new ArrayList<>();
                    resolved.forEach(songList::add);
                    log.info("Resolved link to {} songs. Submitting batch download.", songList.size());
                    broadcast(Map.of(
                            "type", "log",
                            "message", "system: resolved link to " + songList.size() + " songs. Queuing batch..."
                    ));
                    downtifyClient.downloadBatch(songList, cleanUrl, false);
                    historyService.record(HistoryEntry.builder()
                            .artist("").title("")
                            .filename(cleanUrl)
                            .source("URL_IMPORT")
                            .reason("pasted link (" + songList.size() + " tracks resolved)")
                            .status("SUCCESS")
                            .build());
                    return DownloadResult.builder().status("queued").filename(cleanUrl).build();
                }
            } catch (Exception e) {
                log.debug("URL resolution via /api/song/url returned: {}, falling back to single download", e.getMessage());
            }

            String filename = downtifyClient.downloadSingle(cleanUrl);
            broadcast(Map.of(
                    "type", "log",
                    "message", "system: download complete -> " + (filename != null ? filename : cleanUrl)
            ));
            dedupService.refreshIndex();
            historyService.record(HistoryEntry.builder()
                    .artist("").title("")
                    .filename(filename != null ? filename : cleanUrl)
                    .source("URL_IMPORT")
                    .reason("pasted link")
                    .status("SUCCESS")
                    .build());
            return DownloadResult.builder().filename(filename).status("done").build();
        } catch (Exception e) {
            log.error("Download failed for URL '{}': {}", cleanUrl, e.getMessage());
            broadcast(Map.of(
                    "type", "log",
                    "message", "system: error downloading link: " + e.getMessage()
            ));
            historyService.record(HistoryEntry.builder()
                    .artist("").title("")
                    .filename(cleanUrl)
                    .source("URL_IMPORT")
                    .reason("pasted link")
                    .status("FAILED")
                    .build());
            return DownloadResult.builder().status("error").errorMessage(e.getMessage()).build();
        }
    }

    public List<String> listDownloadedFiles() {
        return downtifyClient.listFiles();
    }

    public byte[] getCoverArt(String file) {
        try {
            return downtifyClient.getCoverArt(file);
        } catch (Exception e) {
            log.debug("Could not fetch cover art for '{}': {}", file, e.getMessage());
            return null;
        }
    }
}