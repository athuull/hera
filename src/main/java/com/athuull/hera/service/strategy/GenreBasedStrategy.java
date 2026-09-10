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
public class GenreBasedStrategy extends AbstractRecommendationStrategy {

    public GenreBasedStrategy(LastFmClient lastFm, DeduplicationService dedupService) {
        super(lastFm, dedupService);
    }

    @Override
    public RecommendationStrategy getStrategy() {
        return RecommendationStrategy.GENRE_BASED;
    }

    @Override
    public List<Recommendation> getRecommendations(RecommendationRequest request, int limit) {
        String username = request.getLastfmUsername();
        log.info("Fetching genre-based recommendations for '{}'", username);

        List<Recommendation> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Set<String> recentlyPlayed = getRecentlyPlayedKeys(username, 50);
        seen.addAll(recentlyPlayed);

        Map<String, Integer> genreScores = new HashMap<>();

        try {
            JsonNode topArtists = lastFm.userGetTopArtists(username, "3month", 10);
            JsonNode artistNodes = topArtists.path("topartists").path("artist");
            if (!artistNodes.isArray() || artistNodes.isEmpty()) return Collections.emptyList();

            for (JsonNode artistNode : artistNodes) {
                String artist = artistNode.path("name").asText();
                int playcount = artistNode.path("playcount").asInt(1);

                try {
                    JsonNode tags = lastFm.artistGetTopTags(artist);
                    JsonNode tagNodes = tags.path("toptags").path("tag");

                    if (tagNodes.isArray()) {
                        for (JsonNode tagNode : tagNodes) {
                            String tag = tagNode.path("name").asText().toLowerCase();
                            if (tag.isEmpty() || tag.equals("seen live") || tag.equals("favorites") || tag.equals("favourite")) continue;
                            genreScores.merge(tag, playcount, Integer::sum);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to get tags for artist '{}': {}", artist, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch top artists for genres: {}", e.getMessage());
            return Collections.emptyList();
        }

        if (genreScores.isEmpty()) return Collections.emptyList();

        List<String> topGenres = genreScores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();

        log.info("Derived top genres for {}: {}", username, topGenres);

        int tagsToUse = topGenres.size();
        int tracksPerTag = Math.max(1, limit / tagsToUse);

        for (String tag : topGenres) {
            try {
                JsonNode topTracks = lastFm.tagGetTopTracks(tag, tracksPerTag);
                JsonNode tracks = topTracks.path("tracks").path("track");

                if (tracks.isArray()) {
                    for (JsonNode track : tracks) {
                        String artist = track.path("artist").path("name").asText("Unknown");
                        String title = track.path("name").asText();

                        Track t = new Track(artist, title, null, null);
                        if (seen.add(t.dedupeKey())) {
                            if (!dedupService.alreadyDownloaded(artist, title)) {
                                int rank = track.path("@attr").path("rank").asInt(1);
                                double score = 1.0 / rank;
                                all.add(new Recommendation(t, "genre:" + tag, score, 0, false, false, null));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Tag tracks failed for '{}': {}", tag, e.getMessage());
            }
        }

        log.info("Genre-based: collected {} tracks across {} genres", all.size(), tagsToUse);
        return rankAndLimit(all, limit);
    }
}
