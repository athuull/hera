package com.athuull.hera.service.strategy;

import com.athuull.hera.client.LastFmClient;
import com.athuull.hera.model.Recommendation;
import com.athuull.hera.model.RecommendationRequest;
import com.athuull.hera.model.RecommendationStrategy;
import com.athuull.hera.service.DeduplicationService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

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
        List<Recommendation> nowRecs = nowListeningStrategy.getRecommendations(request, nowListeningQuota);
        for (Recommendation r : nowRecs) {
            if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                all.add(r);
            }
        }
        log.info("Hybrid: {} recs from Now Listening", all.size());

        int personalizedQuota = Math.max(1, (int) (limit * 0.35));
        RecommendationRequest personalizedReq = RecommendationRequest.builder()
                .strategy(RecommendationStrategy.USER_PERSONALIZED)
                .lastfmUsername(username)
                .period("3month")
                .limit(personalizedQuota)
                .build();
        List<Recommendation> userRecs = userPersonalizedStrategy.getRecommendations(personalizedReq, personalizedQuota);
        for (Recommendation r : userRecs) {
            if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                all.add(r);
            }
        }
        log.info("Hybrid: {} total after User Personalized", all.size());

        int genreQuota = limit - all.size();
        if (genreQuota > 0) {
            List<Recommendation> genreRecs = genreBasedStrategy.getRecommendations(request, genreQuota);
            for (Recommendation r : genreRecs) {
                if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                    all.add(r);
                }
            }
            log.info("Hybrid: {} total after Genre Based", all.size());
        }

        return rankAndLimit(all, limit);
    }
}
