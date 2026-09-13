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

/**
 * Discovers tracks musically similar to a specific seed song (artist + track title).
 * Queries {@code track.getSimilar} directly on Last.fm.
 */
@Component
public class TrackSimilarityStrategy extends AbstractRecommendationStrategy {

    public TrackSimilarityStrategy(LastFmClient lastFm, DeduplicationService dedupService) {
        super(lastFm, dedupService);
    }

    @Override
    public RecommendationStrategy getStrategy() {
        return RecommendationStrategy.TRACK_SIMILARITY;
    }

    @Override
    public List<Recommendation> getRecommendations(RecommendationRequest request, int limit) {
        String seedArtist = request.getSeedArtist();
        String seedTrack = request.getSeedTrack();
        if (seedArtist == null || seedTrack == null || seedArtist.isBlank() || seedTrack.isBlank()) {
            return Collections.emptyList();
        }

        List<Recommendation> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            JsonNode similar = lastFm.trackGetSimilar(seedArtist, seedTrack, limit * 2);
            JsonNode tracks = similar.path("similartracks").path("track");

            if (tracks.isArray()) {
                for (JsonNode track : tracks) {
                    String artist = track.path("artist").path("name").asText(
                            track.path("artist").asText("Unknown"));
                    String title = track.path("name").asText();
                    int listeners = track.path("listeners").asInt(0);
                    double match = track.path("match").asDouble(0.5);

                    Track t = new Track(artist, title, null, null);
                    if (seen.add(t.dedupeKey())) {
                        if (!dedupService.alreadyDownloaded(artist, title)) {
                            all.add(new Recommendation(t, seedArtist + " - " + seedTrack, match, listeners, false, false, null));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Track similarity failed for '{} - {}': {}", seedArtist, seedTrack, e.getMessage());
        }

        return rankAndLimit(all, limit);
    }
}
