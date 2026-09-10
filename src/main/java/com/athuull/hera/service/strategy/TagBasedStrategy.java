package com.athuull.hera.service.strategy;

import com.athuull.hera.client.LastFmClient;
import com.athuull.hera.model.Recommendation;
import com.athuull.hera.model.RecommendationRequest;
import com.athuull.hera.model.RecommendationStrategy;
import com.athuull.hera.model.Track;
import com.athuull.hera.service.DeduplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class TagBasedStrategy extends AbstractRecommendationStrategy {

    public TagBasedStrategy(LastFmClient lastFm, DeduplicationService dedupService) {
        super(lastFm, dedupService);
    }

    @Override
    public RecommendationStrategy getStrategy() {
        return RecommendationStrategy.TAG_BASED;
    }

    @Override
    public List<Recommendation> getRecommendations(RecommendationRequest request, int limit) {
        String tag = request.getTag();
        if (tag == null || tag.isBlank()) return Collections.emptyList();

        List<Recommendation> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            JsonNode topTracks = lastFm.tagGetTopTracks(tag.trim(), limit * 2);
            JsonNode tracks = topTracks.path("tracks").path("track");

            if (tracks.isArray()) {
                for (JsonNode track : tracks) {
                    String artist = track.path("artist").path("name").asText("Unknown");
                    String title = track.path("name").asText();
                    int listeners = track.path("listeners").asInt(0);

                    Track t = new Track(artist, title, null, null);
                    if (seen.add(t.dedupeKey())) {
                        if (!dedupService.alreadyDownloaded(artist, title)) {
                            all.add(new Recommendation(t, "tag:" + tag, 0.5, listeners, false, false, null));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Tag-based recommendations failed for '{}': {}", tag, e.getMessage());
        }

        return rankAndLimit(all, limit);
    }
}
