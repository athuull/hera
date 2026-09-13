package com.athuull.hera.service;

import com.athuull.hera.model.AppSettings;
import com.athuull.hera.model.Recommendation;
import com.athuull.hera.model.RecommendationRequest;
import com.athuull.hera.model.RecommendationStrategy;
import com.athuull.hera.service.strategy.RecommendationStrategyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final SettingsService settingsService;
    private final DeduplicationService dedupService;
    private final Map<RecommendationStrategy, RecommendationStrategyProvider> strategyMap;

    @Autowired
    public RecommendationService(SettingsService settingsService,
                                  DeduplicationService dedupService,
                                  List<RecommendationStrategyProvider> providers) {
        this.settingsService = settingsService;
        this.dedupService = dedupService;
        this.strategyMap = providers.stream()
                .collect(Collectors.toMap(RecommendationStrategyProvider::getStrategy, Function.identity()));
    }

    private static final int MAX_SEARCH_LIMIT = 100;
    private static final int DEFAULT_SEARCH_LIMIT = 25;

    public List<Recommendation> getRecommendations(RecommendationRequest request) {
        AppSettings appSettings = settingsService.getSettings();

        int limit = (request.getLimit() != null && request.getLimit() > 0)
                ? Math.min(request.getLimit(), MAX_SEARCH_LIMIT)
                : DEFAULT_SEARCH_LIMIT;

        RecommendationStrategy strategy = request.getStrategy();
        if (strategy == null) {
            strategy = RecommendationStrategy.USER_PERSONALIZED;
        }

        String username = (request.getLastfmUsername() != null && !request.getLastfmUsername().isBlank())
                ? request.getLastfmUsername().trim()
                : (appSettings.getLastfmUsername() != null ? appSettings.getLastfmUsername().trim() : "");

        if (requiresUsername(strategy) && username.isBlank()) {
            log.error("Strategy {} requires a Last.fm username but none is configured", strategy);
            return Collections.emptyList();
        }

        request.setLastfmUsername(username);

        log.info("Generating recommendations via {} strategy for user '{}', limit={}", strategy, username, limit);

        // If pre-warmed HYBRID recommendations exist for this user and are fresh (< 10 minutes), return INSTANTLY (<1ms)
        if (strategy == RecommendationStrategy.HYBRID && limit <= 20
                && prewarmedHybridRecs != null
                && (username.isBlank() || prewarmedUser == null || username.equalsIgnoreCase(prewarmedUser))
                && (System.currentTimeMillis() - prewarmedTimestamp < 600_000L)) {
            log.info("Serving {} pre-warmed HYBRID recommendations directly from memory cache (<1ms)", limit);
            return new ArrayList<>(prewarmedHybridRecs.subList(0, Math.min(limit, prewarmedHybridRecs.size())));
        }

        // If pre-warming is actively running for this user and HYBRID strategy is requested, await in-flight result briefly (max 4s)
        if (strategy == RecommendationStrategy.HYBRID && limit <= 20 && isWarming.get() && warmingFuture != null) {
            try {
                log.info("HYBRID recommendation requested while pre-warmer is running — awaiting in-flight prewarm result (up to 4s)...");
                List<Recommendation> recs = warmingFuture.get(4, java.util.concurrent.TimeUnit.SECONDS);
                if (recs != null && !recs.isEmpty()) {
                    log.info("Returning {} in-flight pre-warmed recommendations", recs.size());
                    return new ArrayList<>(recs.subList(0, Math.min(limit, recs.size())));
                }
            } catch (Exception e) {
                log.debug("In-flight prewarm wait elapsed or failed: {}", e.getMessage());
            }
        }

        // Refresh deduplication index once at the beginning of recommendation run
        dedupService.refreshIndex();

        RecommendationStrategyProvider provider = strategyMap.get(strategy);
        if (provider == null) {
            log.error("No provider registered for recommendation strategy: {}", strategy);
            return Collections.emptyList();
        }

        List<Recommendation> recs = provider.getRecommendations(request, limit);
        log.info("Generated {} recommendations", recs.size());
        return recs;
    }

    public List<Recommendation> getRecommendations(String seedArtist, int limit) {
        return getRecommendations(RecommendationRequest.builder()
                .strategy(RecommendationStrategy.ARTIST_SIMILARITY)
                .seedArtists(List.of(seedArtist))
                .limit(limit)
                .build());
    }

    public List<Recommendation> getRecommendationsFromConfig() {
        AppSettings appSettings = settingsService.getSettings();
        if (appSettings.getLastfmUsername() == null || appSettings.getLastfmUsername().isBlank()) {
            log.error("No Last.fm username configured — scheduled pipeline cannot run. Set it in the WebUI.");
            return Collections.emptyList();
        }

        int nightlyLimit = appSettings.getMaxDailyDownloads() > 0 ? appSettings.getMaxDailyDownloads() : 5;
        RecommendationStrategy strategy = appSettings.getScheduledStrategy() != null
                ? appSettings.getScheduledStrategy()
                : RecommendationStrategy.HYBRID;

        return getRecommendations(RecommendationRequest.builder()
                .strategy(strategy)
                .lastfmUsername(appSettings.getLastfmUsername())
                .limit(nightlyLimit)
                .build());
    }

    public boolean requiresUsername(RecommendationStrategy strategy) {
        return strategy == RecommendationStrategy.USER_PERSONALIZED ||
                strategy == RecommendationStrategy.NOW_LISTENING ||
                strategy == RecommendationStrategy.GENRE_BASED ||
                strategy == RecommendationStrategy.HYBRID;
    }

    private final java.util.concurrent.atomic.AtomicBoolean isWarming = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile java.util.concurrent.CompletableFuture<List<Recommendation>> warmingFuture = null;
    private volatile List<Recommendation> prewarmedHybridRecs = null;
    private volatile String prewarmedUser = null;
    private volatile long prewarmedTimestamp = 0;

    public void prewarmCacheAsync(String username) {
        if (username == null || username.isBlank()) return;
        AppSettings s = settingsService.getSettings();
        if (s == null || s.getLastfmApiKey() == null || s.getLastfmApiKey().isBlank()) return;

        if (!isWarming.compareAndSet(false, true)) {
            return;
        }

        warmingFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Starting background cache pre-warming for user '{}'...", username);
                RecommendationStrategyProvider hybrid = strategyMap.get(RecommendationStrategy.HYBRID);
                if (hybrid != null) {
                    RecommendationRequest req = RecommendationRequest.builder()
                            .strategy(RecommendationStrategy.HYBRID)
                            .lastfmUsername(username)
                            .limit(20)
                            .build();
                    List<Recommendation> recs = hybrid.getRecommendations(req, 20);
                    prewarmedHybridRecs = recs;
                    prewarmedUser = username;
                    prewarmedTimestamp = System.currentTimeMillis();
                    log.info("Background cache pre-warming complete ({} recommendations primed and ready!).", recs.size());
                    return recs;
                }
            } catch (Exception e) {
                log.debug("Cache pre-warming finished with message: {}", e.getMessage());
            } finally {
                isWarming.set(false);
            }
            return Collections.emptyList();
        });
    }
}