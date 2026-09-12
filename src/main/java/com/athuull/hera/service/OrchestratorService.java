package com.athuull.hera.service;

import com.athuull.hera.model.DownloadResult;
import com.athuull.hera.model.HistoryEntry;
import com.athuull.hera.model.Recommendation;
import com.athuull.hera.model.RecommendationRequest;
import com.athuull.hera.model.RecommendationStrategy;
import com.athuull.hera.model.Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

@Service
public class OrchestratorService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);
    private final RecommendationService recommendationService;
    private final DownloadService downloadService;
    private final SettingsService settingsService;
    private final HistoryService historyService;
    private final TaskScheduler taskScheduler;
    private final TaskExecutor taskExecutor;

    private ScheduledFuture<?> scheduledFuture;

    @Autowired
    public OrchestratorService(RecommendationService recommendationService,
                               DownloadService downloadService,
                               SettingsService settingsService,
                               HistoryService historyService,
                               TaskScheduler taskScheduler,
                               @Qualifier("heraTaskExecutor") TaskExecutor taskExecutor) {
        this.recommendationService = recommendationService;
        this.downloadService = downloadService;
        this.settingsService = settingsService;
        this.historyService = historyService;
        this.taskScheduler = taskScheduler;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void run(ApplicationArguments args) {
        String cron = settingsService.getSettings() != null ? settingsService.getSettings().getCronSchedule() : null;
        if (cron != null && !cron.isBlank()) {
            rescheduleCron(cron);
        }
    }

    public synchronized void rescheduleCron(String cronExpression) {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
        if (cronExpression == null || cronExpression.isBlank()) {
            log.info("No cron expression provided; nightly scheduler is disabled.");
            return;
        }
        try {
            CronTrigger trigger = new CronTrigger(cronExpression);
            scheduledFuture = taskScheduler.schedule(this::scheduledRun, trigger);
            log.info("Scheduled nightly pipeline with cron: {}", cronExpression);
        } catch (Exception e) {
            log.error("Invalid cron expression '{}': {}", cronExpression, e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("=== Hera Music Downloader Starting ===");
        downloadService.configureDowntify();
        log.info("Ready. Scheduled downloads will run per cron schedule.");
    }

    public void scheduledRun() {
        runScheduledPipeline();
    }

    public void triggerScheduledPipelineAsync() {
        taskExecutor.execute(this::runScheduledPipeline);
    }

    public void runScheduledPipeline() {
        log.info("=== Scheduled pipeline starting ===");
        try {
            List<Recommendation> recs = recommendationService.getRecommendationsFromConfig();
            if (recs.isEmpty()) {
                log.info("No recommendations generated. Check if Last.fm username is set in settings.");
                return;
            }

            String strategyName = settingsService.getSettings().getScheduledStrategy() != null
                    ? settingsService.getSettings().getScheduledStrategy().name().toLowerCase().replace('_', ' ')
                    : "hybrid";

            List<Track> tracks = recs.stream().map(Recommendation::getTrack).collect(Collectors.toList());
            List<DownloadResult> results = downloadService.downloadBatch(tracks);

            // Record history for each result
            for (int i = 0; i < results.size(); i++) {
                DownloadResult r = results.get(i);
                Track t = r.getTrack() != null ? r.getTrack() : (i < tracks.size() ? tracks.get(i) : null);
                String reason = "nightly " + strategyName + " discovery";
                if (i < recs.size() && recs.get(i).getSourceArtist() != null && !recs.get(i).getSourceArtist().isEmpty()) {
                    reason += " (similar to " + recs.get(i).getSourceArtist() + ")";
                }
                historyService.record(HistoryEntry.builder()
                        .artist(t != null ? t.getArtist() : "")
                        .title(t != null ? t.getTitle() : "")
                        .filename(r.getFilename())
                        .source("NIGHTLY_AUTO")
                        .reason(reason)
                        .status(r.isDone() ? "SUCCESS" : r.isError() ? "FAILED" : "SKIPPED")
                        .build());
            }

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
        List<DownloadResult> results = downloadService.downloadBatch(tracks);

        // Record history for manual downloads
        for (int i = 0; i < results.size(); i++) {
            DownloadResult r = results.get(i);
            Track t = r.getTrack() != null ? r.getTrack() : (i < tracks.size() ? tracks.get(i) : null);
            historyService.record(HistoryEntry.builder()
                    .artist(t != null ? t.getArtist() : "")
                    .title(t != null ? t.getTitle() : "")
                    .filename(r.getFilename())
                    .source("MANUAL_SEARCH")
                    .reason("manual " + request.getStrategy().name().toLowerCase().replace('_', ' '))
                    .status(r.isDone() ? "SUCCESS" : r.isError() ? "FAILED" : "SKIPPED")
                    .build());
        }

        return results;
    }
}