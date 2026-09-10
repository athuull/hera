package com.lo.musicdownloader.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.lo.musicdownloader.client.DowntifyClient;
import com.lo.musicdownloader.client.LastFmClient;
import com.lo.musicdownloader.model.*;
import com.lo.musicdownloader.service.DownloadService;
import com.lo.musicdownloader.service.FormatCleanupService;
import com.lo.musicdownloader.service.OrchestratorService;
import com.lo.musicdownloader.service.RecommendationService;
import com.lo.musicdownloader.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MusicController {

    private final OrchestratorService orchestrator;
    private final DownloadService downloadService;
    private final RecommendationService recommendationService;
    private final LastFmClient lastFmClient;
    private final DowntifyClient downtifyClient;
    private final FormatCleanupService formatCleanupService;
    private final SettingsService settingsService;

    @Autowired
    public MusicController(OrchestratorService orchestrator,
                           DownloadService downloadService,
                           RecommendationService recommendationService,
                           LastFmClient lastFmClient,
                           DowntifyClient downtifyClient,
                           FormatCleanupService formatCleanupService,
                           SettingsService settingsService) {
        this.orchestrator = orchestrator;
        this.downloadService = downloadService;
        this.recommendationService = recommendationService;
        this.lastFmClient = lastFmClient;
        this.downtifyClient = downtifyClient;
        this.formatCleanupService = formatCleanupService;
        this.settingsService = settingsService;
    }

    // ─── Personalized Recommendations ───

    @GetMapping("/recommend/personal")
    public ResponseEntity<List<Recommendation>> recommendPersonalized(
            @RequestParam String username,
            @RequestParam(defaultValue = "overall") String period,
            @RequestParam(defaultValue = "30") int limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(
                RecommendationRequest.builder().strategy(RecommendationStrategy.USER_PERSONALIZED)
                        .lastfmUsername(username).period(period).limit(limit).build()));
    }

    @GetMapping("/recommend/now")
    public ResponseEntity<List<Recommendation>> recommendNowListening(
            @RequestParam String username,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(
                RecommendationRequest.builder().strategy(RecommendationStrategy.NOW_LISTENING)
                        .lastfmUsername(username).limit(limit).build()));
    }

    @GetMapping("/recommend/genres")
    public ResponseEntity<List<Recommendation>> recommendByMyGenres(
            @RequestParam String username,
            @RequestParam(defaultValue = "25") int limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(
                RecommendationRequest.builder().strategy(RecommendationStrategy.GENRE_BASED)
                        .lastfmUsername(username).limit(limit).build()));
    }

    @GetMapping("/recommend/hybrid")
    public ResponseEntity<List<Recommendation>> recommendHybrid(
            @RequestParam String username,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(
                RecommendationRequest.builder().strategy(RecommendationStrategy.HYBRID)
                        .lastfmUsername(username).limit(limit).build()));
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
                RecommendationRequest.builder().strategy(RecommendationStrategy.TRACK_SIMILARITY)
                        .seedArtist(artist).seedTrack(track).limit(limit).build()));
    }

    @GetMapping("/recommend/tag")
    public ResponseEntity<List<Recommendation>> recommendByTag(
            @RequestParam String tag,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(
                RecommendationRequest.builder().strategy(RecommendationStrategy.TAG_BASED)
                        .tag(tag).limit(limit).build()));
    }

    // ─── Downloads ───

    @PostMapping("/download")
    public ResponseEntity<List<DownloadResult>> download(@RequestBody RecommendationRequest request) {
        return ResponseEntity.ok(orchestrator.runManual(request));
    }

    @PostMapping("/download/tracks")
    public ResponseEntity<String> downloadTracks(@RequestBody DownloadTracksRequest request) {
        if (request.getTracks() == null || request.getTracks().isEmpty()) {
            return ResponseEntity.badRequest().body("No tracks provided");
        }
        new Thread(() -> downloadService.downloadBatch(request.getTracks())).start();
        return ResponseEntity.ok("Download started for " + request.getTracks().size() + " tracks");
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
        return ResponseEntity.ok(downloadService.listDownloadedFiles());
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
        new Thread(() -> downloadService.configureDowntify()).start();
        return ResponseEntity.ok(updated);
    }

    // ─── Scheduler ───

    @PostMapping("/schedule/trigger")
    public ResponseEntity<String> triggerSchedule() {
        new Thread(() -> orchestrator.runScheduledPipeline()).start();
        return ResponseEntity.ok("Pipeline triggered — check logs for progress");
    }
}