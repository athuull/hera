package com.athuull.hera.service;

import com.athuull.hera.model.DownloadResult;
import com.athuull.hera.model.Recommendation;
import com.athuull.hera.model.RecommendationRequest;
import com.athuull.hera.model.RecommendationStrategy;
import com.athuull.hera.model.Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);
    private final RecommendationService recommendationService;
    private final DownloadService downloadService;

    @Autowired
    public OrchestratorService(RecommendationService recommendationService, DownloadService downloadService) {
        this.recommendationService = recommendationService;
        this.downloadService = downloadService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("=== Music Downloader Starting ===");
        downloadService.configureDowntify();
        log.info("Ready. Scheduled downloads will run per cron schedule.");
    }

    @Scheduled(cron = "${scheduler.cron}")
    public void scheduledRun() {
        runScheduledPipeline();
    }

    public void runScheduledPipeline() {
        log.info("=== Scheduled pipeline starting ===");
        try {
            List<Recommendation> recs = recommendationService.getRecommendationsFromConfig();
            if (recs.isEmpty()) {
                log.info("No recommendations generated. Check if Last.fm username is set in settings.");
                return;
            }

            List<Track> tracks = recs.stream().map(Recommendation::getTrack).collect(Collectors.toList());
            List<DownloadResult> results = downloadService.downloadBatch(tracks);

            long succeeded = results.stream().filter(DownloadResult::isDone).count();
            long failed = results.stream().filter(DownloadResult::isError).count();
            log.info("=== Pipeline complete: {} downloaded, {} failed ===", succeeded, failed);
        } catch (Exception e) {
            log.error("Pipeline error: {}", e.getMessage(), e);
        }
    }

    public List<DownloadResult> runManual(RecommendationRequest request) {
        log.info("=== Manual download: {} ===", request.getStrategy());
        List<Recommendation> recs = recommendationService.getRecommendations(request);
        List<Track> tracks = recs.stream().map(Recommendation::getTrack).collect(Collectors.toList());
        return downloadService.downloadBatch(tracks);
    }

    public List<DownloadResult> runManual(List<String> seedArtists, int limit) {
        return runManual(RecommendationRequest.builder()
                .strategy(RecommendationStrategy.ARTIST_SIMILARITY)
                .seedArtists(seedArtists).limit(limit).build());
    }
}