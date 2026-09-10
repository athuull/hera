package com.athuull.hera.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.athuull.hera.client.DowntifyClient;
import com.athuull.hera.model.AppSettings;
import com.athuull.hera.model.DownloadResult;
import com.athuull.hera.model.DownloadTracksRequest;
import com.athuull.hera.model.Recommendation;
import com.athuull.hera.model.RecommendationRequest;
import com.athuull.hera.model.RecommendationStrategy;
import com.athuull.hera.service.DownloadService;
import com.athuull.hera.service.FormatCleanupService;
import com.athuull.hera.service.OrchestratorService;
import com.athuull.hera.service.RecommendationService;
import com.athuull.hera.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MusicController {

    private final OrchestratorService orchestrator;
    private final DownloadService downloadService;
    private final RecommendationService recommendationService;
    private final DowntifyClient downtifyClient;
    private final FormatCleanupService formatCleanupService;
    private final SettingsService settingsService;
    private final TaskExecutor taskExecutor;

    @Autowired
    public MusicController(OrchestratorService orchestrator,
                           DownloadService downloadService,
                           RecommendationService recommendationService,
                           DowntifyClient downtifyClient,
                           FormatCleanupService formatCleanupService,
                           SettingsService settingsService,
                           @Qualifier("heraTaskExecutor") TaskExecutor taskExecutor) {
        this.orchestrator = orchestrator;
        this.downloadService = downloadService;
        this.recommendationService = recommendationService;
        this.downtifyClient = downtifyClient;
        this.formatCleanupService = formatCleanupService;
        this.settingsService = settingsService;
        this.taskExecutor = taskExecutor;
    }

    // ─── Personalized Recommendations ───

    @GetMapping("/recommend/personal")
    public ResponseEntity<List<Recommendation>> recommendPersonalized(
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "overall") String period,
            @RequestParam(defaultValue = "30") int limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(
                RecommendationRequest.builder()
                        .strategy(RecommendationStrategy.USER_PERSONALIZED)
                        .lastfmUsername(username)
                        .period(period)
                        .limit(limit)
                        .build()));
    }

    @GetMapping("/recommend/now")
    public ResponseEntity<List<Recommendation>> recommendNowListening(
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(
                RecommendationRequest.builder()
                        .strategy(RecommendationStrategy.NOW_LISTENING)
                        .lastfmUsername(username)
                        .limit(limit)
                        .build()));
    }

    @GetMapping("/recommend/genres")
    public ResponseEntity<List<Recommendation>> recommendByMyGenres(
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "25") int limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(
                RecommendationRequest.builder()
                        .strategy(RecommendationStrategy.GENRE_BASED)
                        .lastfmUsername(username)
                        .limit(limit)
                        .build()));
    }

    @GetMapping("/recommend/hybrid")
    public ResponseEntity<List<Recommendation>> recommendHybrid(
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(
                RecommendationRequest.builder()
                        .strategy(RecommendationStrategy.HYBRID)
                        .lastfmUsername(username)
                        .limit(limit)
                        .build()));
    }

    // ─── Manual Recommendations ───

    @GetMapping("/recommend")
    public ResponseEntity<List<Recommendation>> recommendByArtist(
            @RequestParam String artist,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(artist, limit));
    }

    @GetMapping("/recommend/track")
    public ResponseEntity<List<Recommendation>> recommendByTrack(
            @RequestParam String artist,
            @RequestParam String track,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(
                RecommendationRequest.builder()
                        .strategy(RecommendationStrategy.TRACK_SIMILARITY)
                        .seedArtist(artist)
                        .seedTrack(track)
                        .limit(limit)
                        .build()));
    }

    @GetMapping("/recommend/tag")
    public ResponseEntity<List<Recommendation>> recommendByTag(
            @RequestParam String tag,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(
                RecommendationRequest.builder()
                        .strategy(RecommendationStrategy.TAG_BASED)
                        .tag(tag)
                        .limit(limit)
                        .build()));
    }

    // ─── Downloads ───

    @PostMapping("/download")
    public ResponseEntity<List<DownloadResult>> download(@RequestBody RecommendationRequest request) {
        return ResponseEntity.ok(orchestrator.runManual(request));
    }

    @PostMapping("/download/tracks")
    public ResponseEntity<Map<String, Object>> downloadTracks(@RequestBody DownloadTracksRequest request) {
        if (request.getTracks() == null || request.getTracks().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tracks provided"));
        }
        taskExecutor.execute(() -> downloadService.downloadBatch(request.getTracks()));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("message", "Download queued for " + request.getTracks().size() + " tracks");
        resp.put("count", request.getTracks().size());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/download/url")
    public ResponseEntity<DownloadResult> downloadUrl(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        if (url == null || url.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(downloadService.downloadSingleByUrl(url));
    }

    // ─── Queue & Library ───

    @GetMapping("/queue")
    public ResponseEntity<JsonNode> getQueue() {
        return ResponseEntity.ok(downtifyClient.getQueue());
    }

    @GetMapping("/library")
    public ResponseEntity<List<String>> library() {
        List<String> files = downloadService.listDownloadedFiles();
        return ResponseEntity.ok(files != null ? files : Collections.emptyList());
    }

    // ─── Format Cleanup ───

    @PostMapping("/cleanup")
    public ResponseEntity<List<String>> cleanupFormats() {
        return ResponseEntity.ok(formatCleanupService.cleanupWebmFiles());
    }

    // ─── Settings ───

    @GetMapping("/settings")
    public ResponseEntity<AppSettings> getSettings() {
        return ResponseEntity.ok(settingsService.getSettings());
    }

    @PostMapping("/settings")
    public ResponseEntity<AppSettings> updateSettings(@RequestBody AppSettings newSettings) {
        AppSettings updated = settingsService.updateSettings(newSettings);

        // Push new audio settings to Downtify asynchronously
        taskExecutor.execute(downloadService::configureDowntify);

        // Reschedule the cron job with the new time
        orchestrator.rescheduleCron(updated.getCronSchedule());

        return ResponseEntity.ok(updated);
    }

    // ─── Scheduler ───

    @PostMapping("/schedule/trigger")
    public ResponseEntity<Map<String, String>> triggerSchedule() {
        orchestrator.triggerScheduledPipelineAsync();
        return ResponseEntity.ok(Map.of("message", "Pipeline triggered — check progress feed for live updates"));
    }
}