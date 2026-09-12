package com.athuull.hera.service.strategy;

import com.athuull.hera.client.LastFmClient;
import com.athuull.hera.model.Recommendation;
import com.athuull.hera.model.RecommendationRequest;
import com.athuull.hera.model.RecommendationStrategy;
import com.athuull.hera.service.DeduplicationService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Component
public class HybridStrategy extends AbstractRecommendationStrategy {

    private final NowListeningStrategy nowListeningStrategy;
    private final UserPersonalizedStrategy userPersonalizedStrategy;
    private final GenreBasedStrategy genreBasedStrategy;

    public HybridStrategy(LastFmClient lastFm,
                          DeduplicationService dedupService,
                          @Lazy NowListeningStrategy nowListeningStrategy,
                          @Lazy UserPersonalizedStrategy userPersonalizedStrategy,
                          @Lazy GenreBasedStrategy genreBasedStrategy) {
        super(lastFm, dedupService);
        this.nowListeningStrategy = nowListeningStrategy;
        this.userPersonalizedStrategy = userPersonalizedStrategy;
        this.genreBasedStrategy = genreBasedStrategy;
    }

    @Override
    public RecommendationStrategy getStrategy() {
        return RecommendationStrategy.HYBRID;
    }

    @Override
    public List<Recommendation> getRecommendations(RecommendationRequest request, int limit) {
        String username = request.getLastfmUsername();
        List<Recommendation> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int nowListeningQuota = Math.max(1, (int) (limit * 0.4));
        int personalizedQuota = Math.max(1, (int) (limit * 0.4));
        int genreQuota = Math.max(1, (int) (limit * 0.3));

        RecommendationRequest personalizedReq = RecommendationRequest.builder()
                .strategy(RecommendationStrategy.USER_PERSONALIZED)
                .lastfmUsername(username)
                .period("3month")
                .limit(personalizedQuota)
                .build();

        // Run all three sub-strategies concurrently
        var nowFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return nowListeningStrategy.getRecommendations(request, nowListeningQuota);
            } catch (Exception e) {
                log.warn("Now Listening sub-strategy failed in Hybrid: {}", e.getMessage());
                return Collections.<Recommendation>emptyList();
            }
        });

        var userFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return userPersonalizedStrategy.getRecommendations(personalizedReq, personalizedQuota);
            } catch (Exception e) {
                log.warn("User Personalized sub-strategy failed in Hybrid: {}", e.getMessage());
                return Collections.<Recommendation>emptyList();
            }
        });

        var genreFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return genreBasedStrategy.getRecommendations(request, genreQuota);
            } catch (Exception e) {
                log.warn("Genre Based sub-strategy failed in Hybrid: {}", e.getMessage());
                return Collections.<Recommendation>emptyList();
            }
        });

        CompletableFuture.allOf(nowFuture, userFuture, genreFuture).join();

        List<Recommendation> nowRecs = nowFuture.join();
        for (Recommendation r : nowRecs) {
            if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                all.add(r);
            }
        }
        log.info("Hybrid: {} recs from Now Listening", all.size());

        List<Recommendation> userRecs = userFuture.join();
        for (Recommendation r : userRecs) {
            if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                all.add(r);
            }
        }
        log.info("Hybrid: {} total after User Personalized", all.size());

        List<Recommendation> genreRecs = genreFuture.join();
        for (Recommendation r : genreRecs) {
            if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                all.add(r);
            }
        }
        log.info("Hybrid: {} total after Genre Based", all.size());

        return rankAndLimit(all, limit);
    }
}
