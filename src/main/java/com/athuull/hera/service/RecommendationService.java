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
}