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

/**
 * Hybrid recommendation strategy combining multiple sub-strategies in parallel.
 * <p>
 * <b>Key Concepts:</b>
 * <ul>
 *   <li><b>Concurrent Fan-Out:</b> Concurrently evaluates {@link NowListeningStrategy} (40% quota),
 *       {@link UserPersonalizedStrategy} (40% quota), and {@link GenreBasedStrategy} (20% quota)
 *       using {@link CompletableFuture}.</li>
 *   <li><b>Lazy Injection:</b> Uses Spring's {@code @Lazy} annotation to break circular dependency
 *       loops during bean initialization.</li>
 *   <li><b>Candidate Pooling & Backfill:</b> Evaluates candidate pools and auto-backfills from remaining
 *       candidates if library deduplication exhausts one category's target quota.</li>
 * </ul>
 */
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

        // Target blend quotas
        int nowListeningQuota = Math.max(1, (int) (limit * 0.4));
        int personalizedQuota = Math.max(1, (int) (limit * 0.4));
        int genreQuota = Math.max(1, (int) (limit * 0.2));

        // Request a full, generous candidate pool from each sub-strategy
        // so that deduplication never starves the final result
        int candidatePoolSize = Math.max(limit, 20);

        RecommendationRequest personalizedReq = RecommendationRequest.builder()
                .strategy(RecommendationStrategy.USER_PERSONALIZED)
                .lastfmUsername(username)
                .period("3month")
                .limit(candidatePoolSize)
                .build();

        // Run all three sub-strategies concurrently
        var nowFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return nowListeningStrategy.getRecommendations(request, candidatePoolSize);
            } catch (Exception e) {
                log.warn("Now Listening sub-strategy failed in Hybrid: {}", e.getMessage());
                return Collections.<Recommendation>emptyList();
            }
        });

        var userFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return userPersonalizedStrategy.getRecommendations(personalizedReq, candidatePoolSize);
            } catch (Exception e) {
                log.warn("User Personalized sub-strategy failed in Hybrid: {}", e.getMessage());
                return Collections.<Recommendation>emptyList();
            }
        });

        var genreFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return genreBasedStrategy.getRecommendations(request, candidatePoolSize);
            } catch (Exception e) {
                log.warn("Genre Based sub-strategy failed in Hybrid: {}", e.getMessage());
                return Collections.<Recommendation>emptyList();
            }
        });

        CompletableFuture.allOf(nowFuture, userFuture, genreFuture).join();

        List<Recommendation> nowRecs = nowFuture.join();
        List<Recommendation> userRecs = userFuture.join();
        List<Recommendation> genreRecs = genreFuture.join();

        // 1. Primary pass: Add up to each category's target quota
        int nowAdded = 0;
        for (Recommendation r : nowRecs) {
            if (nowAdded >= nowListeningQuota) break;
            if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                all.add(r);
                nowAdded++;
            }
        }

        int userAdded = 0;
        for (Recommendation r : userRecs) {
            if (userAdded >= personalizedQuota) break;
            if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                all.add(r);
                userAdded++;
            }
        }

        int genreAdded = 0;
        for (Recommendation r : genreRecs) {
            if (genreAdded >= genreQuota) break;
            if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                all.add(r);
                genreAdded++;
            }
        }

        // 2. Backfill pass: If deduplication dropped any quota slots,
        // backfill from remaining candidates to ensure 100% of 'limit' is met!
        if (all.size() < limit) {
            List<Recommendation> remaining = new ArrayList<>();
            remaining.addAll(userRecs);
            remaining.addAll(nowRecs);
            remaining.addAll(genreRecs);

            for (Recommendation r : remaining) {
                if (all.size() >= limit) break;
                if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                    all.add(r);
                }
            }
        }

        log.info("Hybrid: returning {} recommendations (requested limit: {})", all.size(), limit);
        return rankAndLimit(all, limit);
    }
}
