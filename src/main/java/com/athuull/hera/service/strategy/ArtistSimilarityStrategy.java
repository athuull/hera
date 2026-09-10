package com.athuull.hera.service.strategy;

import com.athuull.hera.client.LastFmClient;
import com.athuull.hera.model.Recommendation;
import com.athuull.hera.model.RecommendationRequest;
import com.athuull.hera.model.RecommendationStrategy;
import com.athuull.hera.service.DeduplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ArtistSimilarityStrategy extends AbstractRecommendationStrategy {

    public ArtistSimilarityStrategy(LastFmClient lastFm, DeduplicationService dedupService) {
        super(lastFm, dedupService);
    }

    @Override
    public RecommendationStrategy getStrategy() {
        return RecommendationStrategy.ARTIST_SIMILARITY;
    }

    @Override
    public List<Recommendation> getRecommendations(RecommendationRequest request, int limit) {
        List<String> seedArtists = request.getSeedArtists();
        if (seedArtists == null || seedArtists.isEmpty()) {
            if (request.getSeedArtist() != null && !request.getSeedArtist().isBlank()) {
                seedArtists = List.of(request.getSeedArtist().trim());
            } else {
                return Collections.emptyList();
            }
        }

        List<Recommendation> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String seed : seedArtists) {
            try {
                JsonNode similar = lastFm.artistGetSimilar(seed, 10);
                JsonNode matches = similar.path("similarartists").path("artist");
                if (!matches.isArray()) continue;

                for (JsonNode match : matches) {
                    String artist = match.path("name").asText();
                    double score = match.path("match").asDouble(0.0);
                    extractTopTracks(artist, seed, score, seen, all);
                }
            } catch (Exception e) {
                log.warn("Artist similarity failed for '{}': {}", seed, e.getMessage());
            }
        }

        return rankAndLimit(all, limit);
    }
}
