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
        if (track == null) return Optional.empty();

        List<String> searchQueries = buildSearchQueries(track);
        for (String query : searchQueries) {
            try {
                JsonNode results = downtifyClient.searchSongs(query);
                if (results != null && results.isArray() && !results.isEmpty()) {
                    for (JsonNode candidate : results) {
                        String cTitle  = extractCandidateTitle(candidate);
                        String cArtist = extractCandidateArtist(candidate);
                        if (isPlausibleMatch(track, cArtist, cTitle)) {
                            log.info("Found YouTube Music match for '{} - {}' via query '{}' -> '{} - {}'",
                                    track.getArtist(), track.getTitle(), query, cArtist, cTitle);
                            return Optional.of(candidate);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Search query '{}' failed for '{} - {}': {}", query, track.getArtist(), track.getTitle(), e.getMessage());
            }
        }

        log.warn("No plausible match across {} search queries for '{} - {}'",
                searchQueries.size(), track.getArtist(), track.getTitle());
        return Optional.empty();
    }

    public List<String> buildSearchQueries(Track track) {
        List<String> queries = new ArrayList<>();
        String rawArtist = track.getArtist() != null ? track.getArtist().trim() : "";
        String rawTitle = track.getTitle() != null ? track.getTitle().trim() : "";
        String cleanArtist = Track.cleanArtist(rawArtist);
        String cleanTitle = Track.cleanTitle(rawTitle);
        String baseTitle = stripParenthesesAndBrackets(rawTitle);

        // 1. Primary query from track
        String primary = track.toSearchQuery();
        if (!primary.isBlank()) queries.add(primary);

        // 2. Clean primary artist + clean title
        if (!cleanArtist.isBlank() && !cleanTitle.isBlank()) {
            String q = (cleanArtist + " " + cleanTitle).trim();
            if (!queries.contains(q)) queries.add(q);
        }

        // 3. Clean primary artist + stripped base title (e.g. Mike Shinoda Heavy Is the Crown)
        if (!cleanArtist.isBlank() && !baseTitle.isBlank()) {
            String q = (cleanArtist + " " + baseTitle).trim();
            if (!queries.contains(q)) queries.add(q);
        }

        // 4. If multiple artists, try each artist individually with clean / base title
        List<String> allArtists = dedupService.extractAllArtists(rawArtist);
        for (String a : allArtists) {
            String q = (a + " " + cleanTitle).trim();
            if (!queries.contains(q)) queries.add(q);
            if (!baseTitle.isBlank()) {
                String qb = (a + " " + baseTitle).trim();
                if (!queries.contains(qb)) queries.add(qb);
            }
        }

        // 5. Fallback to title only if distinctive
        if (!cleanTitle.isBlank() && DeduplicationService.isDistinctiveTitle(cleanTitle)) {
            if (!queries.contains(cleanTitle)) queries.add(cleanTitle);
        }
        if (!baseTitle.isBlank() && DeduplicationService.isDistinctiveTitle(baseTitle)) {
            if (!queries.contains(baseTitle)) queries.add(baseTitle);
        }

        return queries;
    }

    public List<DownloadResult> downloadBatch(List<Track> tracks) {
        return downloadBatch(tracks, null, null);
    }

    public List<DownloadResult> downloadBatch(List<Track> tracks, String source, String reason) {
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
                if (source != null) {
                    recordBatchHistory(results, tracks, source, reason);
                }
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
                if (source != null) {
                    recordBatchHistory(results, tracks, source, reason);
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

            if (source != null) {
                recordBatchHistory(results, tracks, source, reason);
            }

            long succeeded = results.stream().filter(DownloadResult::isDone).count();
            long failed = results.stream().filter(DownloadResult::isError).count();
            broadcastBatchComplete(totalTracks, succeeded, failed, skipped);

            return results;
        } finally {
            downloadLock.unlock();
        }
    }

    private void recordBatchHistory(List<DownloadResult> results, List<Track> tracks, String source, String reason) {
        for (int i = 0; i < results.size(); i++) {
            DownloadResult r = results.get(i);
            Track t = r.getTrack() != null ? r.getTrack() : (i < tracks.size() ? tracks.get(i) : null);
            historyService.record(HistoryEntry.builder()
                    .artist(t != null && t.getArtist() != null ? t.getArtist() : "")
                    .title(t != null && t.getTitle() != null ? t.getTitle() : "")
                    .filename(r.getFilename())
                    .source(source)
                    .reason(reason != null ? reason : "batch download")
                    .status(r.isDone() ? "SUCCESS" : r.isError() ? "FAILED" : "SKIPPED")
                    .build());
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

        boolean titleMatches = gotTitle.equals(reqTitle);
        if (!titleMatches) return false;

        String reqArtist = cleanForComparison(requested.getArtist());
        String gotArtist = matchedArtist == null ? "" : cleanForComparison(matchedArtist);

        if (gotArtist.isBlank() || reqArtist.isBlank()) {
            return true;
        }

        if (gotArtist.contains(reqArtist)) {
            return true;
        }

        List<String> reqArtists = dedupService.extractAllArtists(requested.getArtist());
        List<String> gotArtists = dedupService.extractAllArtists(matchedArtist);
        for (String rA : reqArtists) {
            for (String gA : gotArtists) {
                if (rA.equals(gA)) {
                    return true;
                }
            }
        }

        return false;
    }

    String stripParenthesesAndBrackets(String input) {
        if (input == null) return "";
        String s = input.toLowerCase();
        s = s.replaceAll("\\([^)]*\\)", "");
        s = s.replaceAll("\\[[^\\]]*\\]", "");
        s = s.replaceAll("\\s+f(?:eat|t)\\..*", "");
        s = s.replaceAll("[^\\p{L}\\p{N}\\p{M}\\s]", "");
        s = s.replaceAll("\\s+", " ");
        return s.trim();
    }

    String cleanTitle(String input) {
        if (input == null) return "";
        String s = input.toLowerCase();
        s = s.replaceAll("\\(\\s*f(?:eat|t|eaturing)\\.?[^)]*\\)", "");
        s = s.replaceAll("\\[\\s*f(?:eat|t|eaturing)\\.?[^\\]]*\\]", "");
        s = s.replaceAll("\\(\\s*(?:with|feat|featuring)\\s+[^)]*\\)", "");
        s = s.replaceAll("\\[\\s*(?:with|feat|featuring)\\s+[^\\]]*\\]", "");
        s = s.replaceAll("\\(\\s*(?:official\\s+(?:video|audio|music\\s+video)|music\\s+video|audio|lyric(?:s|\\s+video)?|visuali[zs]er|hd|hq|explicit|clean|remastered|deluxe|original\\s+score|from\\s+the\\s+series[^)]*|from\\s+the\\s+motion\\s+picture[^)]*|from\\s+[^)]*|soundtrack\\s+version|original\\s+motion\\s+picture\\s+soundtrack|ost)\\s*\\)", "");
        s = s.replaceAll("\\[\\s*(?:official\\s+(?:video|audio|music\\s+video)|music\\s+video|audio|lyric(?:s|\\s+video)?|visuali[zs]er|hd|hq|explicit|clean|remastered|deluxe|original\\s+score|from\\s+the\\s+series[^\\]]*|from\\s+the\\s+motion\\s+picture[^\\]]*|from\\s+[^\\]]*|soundtrack\\s+version|original\\s+motion\\s+picture\\s+soundtrack|ost)\\s*\\]", "");
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
        Set<String> batchIdentifiers = new HashSet<>();

        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            trackLookup.put(t.dedupeKey(), t);
            if (i < songNodes.size()) {
                JsonNode sn = songNodes.get(i);
                String mTitle  = extractCandidateTitle(sn);
                String mArtist = extractCandidateArtist(sn);
                Track matchedT = new Track(mArtist, mTitle, null, null);
                trackLookup.put(matchedT.dedupeKey(), t);

                if (sn.hasNonNull("id")) {
                    String id = sn.get("id").asText();
                    trackLookup.put(id, t);
                    batchIdentifiers.add(id);
                }
                if (sn.hasNonNull("videoId")) {
                    String vid = sn.get("videoId").asText();
                    trackLookup.put(vid, t);
                    batchIdentifiers.add(vid);
                }
                if (sn.hasNonNull("song_id")) {
                    String sid = sn.get("song_id").asText();
                    trackLookup.put(sid, t);
                    batchIdentifiers.add(sid);
                }
                if (sn.hasNonNull("url")) {
                    String u = sn.get("url").asText();
                    trackLookup.put(u, t);
                    batchIdentifiers.add(u);
                }
                if (!mTitle.isBlank()) {
                    batchIdentifiers.add(cleanTitle(mTitle));
                }
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
                String filename = job.path("filename").asText(null);
                if (!jobBelongsToBatch(job, filename, trackLookup, batchIdentifiers)) {
                    continue;
                }

                String status = job.path("status").asText("unknown");

                if ("done".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status)) {
                    completedInThisPoll++;
                    Track matchedTrack = resolveTrackForJob(job, filename, trackLookup);
                    newResults.add(DownloadResult.builder()
                            .filename(filename)
                            .status("done")
                            .track(matchedTrack)
                            .build());
                } else if ("error".equalsIgnoreCase(status)) {
                    completedInThisPoll++;
                    String error = job.has("error") ? job.get("error").asText() : "Unknown error";
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

    private boolean jobBelongsToBatch(JsonNode job, String filename, Map<String, Track> lookup, Set<String> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) return true;
        if (job.hasNonNull("id") && identifiers.contains(job.get("id").asText())) return true;
        if (job.hasNonNull("song_id") && identifiers.contains(job.get("song_id").asText())) return true;
        if (job.hasNonNull("videoId") && identifiers.contains(job.get("videoId").asText())) return true;
        if (job.hasNonNull("url") && identifiers.contains(job.get("url").asText())) return true;

        if (job.hasNonNull("name") && identifiers.contains(cleanTitle(job.get("name").asText()))) return true;
        if (job.hasNonNull("title") && identifiers.contains(cleanTitle(job.get("title").asText()))) return true;

        JsonNode song = job.path("song");
        if (song.isObject()) {
            if (song.hasNonNull("id") && identifiers.contains(song.get("id").asText())) return true;
            if (song.hasNonNull("song_id") && identifiers.contains(song.get("song_id").asText())) return true;
            if (song.hasNonNull("name") && identifiers.contains(cleanTitle(song.get("name").asText()))) return true;
            if (song.hasNonNull("title") && identifiers.contains(cleanTitle(song.get("title").asText()))) return true;
        }

        if (filename != null && !filename.isBlank()) {
            String key = DeduplicationService.normalizeFilename(filename);
            if (lookup.containsKey(key)) return true;
        }
        return false;
    }

    private Track resolveTrackForJob(JsonNode job, String filename, Map<String, Track> lookup) {
        if (filename != null && !filename.isBlank()) {
            String key = DeduplicationService.normalizeFilename(filename);
            if (lookup.containsKey(key)) return lookup.get(key);
        }
        if (job.has("id") && lookup.containsKey(job.get("id").asText())) {
            return lookup.get(job.get("id").asText());
        }
        if (job.has("song_id") && lookup.containsKey(job.get("song_id").asText())) {
            return lookup.get(job.get("song_id").asText());
        }
        JsonNode song = job.path("song");
        if (song.isObject()) {
            if (song.has("id") && lookup.containsKey(song.get("id").asText())) return lookup.get(song.get("id").asText());
            if (song.has("song_id") && lookup.containsKey(song.get("song_id").asText())) return lookup.get(song.get("song_id").asText());
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

            List<JsonNode> songList = new ArrayList<>();
            try {
                JsonNode resolved = downtifyClient.resolveUrl(cleanUrl);
                if (resolved != null) {
                    if (resolved.isArray() && !resolved.isEmpty()) {
                        resolved.forEach(songList::add);
                    } else if (resolved.isObject() && (resolved.hasNonNull("song_id") || resolved.hasNonNull("id") || resolved.hasNonNull("name") || resolved.hasNonNull("url"))) {
                        songList.add(resolved);
                    }
                }
            } catch (Exception e) {
                log.debug("URL resolution via /api/song/url returned: {}, falling back to single download", e.getMessage());
            }

            if (!songList.isEmpty()) {
                log.info("Resolved link to {} songs. Submitting batch download.", songList.size());
                broadcast(Map.of(
                        "type", "log",
                        "message", "system: resolved link to " + songList.size() + " songs. Queuing batch..."
                ));

                List<Track> resolvedTracks = new ArrayList<>();
                List<JsonNode> unskippedSongs = new ArrayList<>();
                List<Track> unskippedTracks = new ArrayList<>();
                int skipped = 0;

                dedupService.refreshIndex();
                broadcastBatchStart(songList.size());

                for (JsonNode sn : songList) {
                    String title = extractCandidateTitle(sn);
                    String artist = extractCandidateArtist(sn);
                    Track t = new Track(artist, title, null, null);
                    resolvedTracks.add(t);

                    if (dedupService.alreadyDownloaded(artist, title)) {
                        skipped++;
                        broadcastStatus(t, "skipped", "already downloaded");
                        historyService.record(HistoryEntry.builder()
                                .artist(artist)
                                .title(title)
                                .filename(cleanUrl)
                                .source("URL_IMPORT")
                                .reason("already in library")
                                .status("SKIPPED")
                                .build());
                    } else {
                        unskippedSongs.add(sn);
                        unskippedTracks.add(t);
                    }
                }

                if (unskippedSongs.isEmpty()) {
                    log.info("All tracks from URL already exist in library");
                    broadcastBatchComplete(songList.size(), 0, 0, skipped);
                    return DownloadResult.builder().status("done").filename(cleanUrl).build();
                }

                try {
                    downtifyClient.clearQueue();
                } catch (Exception e) {
                    log.warn("Could not clear queue before batch: {}", e.getMessage());
                }

                downtifyClient.downloadBatch(unskippedSongs, cleanUrl, false);
                List<DownloadResult> polledResults = pollQueueUntilComplete(
                        unskippedSongs.size(), unskippedTracks, unskippedSongs, songList.size(), skipped);

                dedupService.refreshIndex();
                for (DownloadResult r : polledResults) {
                    Track t = r.getTrack();
                    historyService.record(HistoryEntry.builder()
                            .artist(t != null && t.getArtist() != null ? t.getArtist() : "")
                            .title(t != null && t.getTitle() != null ? t.getTitle() : "")
                            .filename(r.getFilename() != null ? r.getFilename() : cleanUrl)
                            .source("URL_IMPORT")
                            .reason("pasted link")
                            .status(r.isDone() ? "SUCCESS" : "FAILED")
                            .build());
                }

                long succeeded = polledResults.stream().filter(DownloadResult::isDone).count();
                long failed = polledResults.stream().filter(DownloadResult::isError).count();
                broadcastBatchComplete(songList.size(), succeeded, failed, skipped);

                return DownloadResult.builder().status(failed == 0 ? "done" : "error").filename(cleanUrl).build();
            }

            // Fallback to downloadSingle
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