package com.athuull.hera.service.strategy;

import com.athuull.hera.client.LastFmClient;
import com.athuull.hera.model.Recommendation;
import com.athuull.hera.model.RecommendationRequest;
import com.athuull.hera.model.RecommendationStrategy;
import com.athuull.hera.service.DeduplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Component
public class UserPersonalizedStrategy extends AbstractRecommendationStrategy {

    public UserPersonalizedStrategy(LastFmClient lastFm, DeduplicationService dedupService) {
        super(lastFm, dedupService);
    }

    @Override
    public RecommendationStrategy getStrategy() {
        return RecommendationStrategy.USER_PERSONALIZED;
    }

    @Override
    public List<Recommendation> getRecommendations(RecommendationRequest request, int limit) {
        String username = request.getLastfmUsername();
        String period = (request.getPeriod() != null && !request.getPeriod().isBlank()) ? request.getPeriod() : "overall";

        log.info("Fetching personalized recommendations for '{}', period='{}'", username, period);

        List<Recommendation> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Set<String> recentlyPlayed = getRecentlyPlayedKeys(username, 50);
        seen.addAll(recentlyPlayed);
        log.info("Loaded {} recently played tracks for dedup", recentlyPlayed.size());

        try {
            int topArtistCount = Math.min(6, Math.max(4, (int) Math.ceil(limit / 3.0)));
            JsonNode topArtists = lastFm.userGetTopArtists(username, period, topArtistCount);
            JsonNode artistNodes = topArtists.path("topartists").path("artist");
            if (!artistNodes.isArray()) return Collections.emptyList();

            List<CompletableFuture<List<Recommendation>>> artistFutures = new ArrayList<>();

            for (JsonNode artistNode : artistNodes) {
                String artist = artistNode.path("name").asText();
                int playcount = artistNode.path("playcount").asInt(0);

                artistFutures.add(CompletableFuture.supplyAsync(() -> {
                    List<Recommendation> artistRecs = new ArrayList<>();
                    try {
                        JsonNode similar = lastFm.artistGetSimilar(artist, 3);
                        JsonNode matches = similar.path("similarartists").path("artist");

                        if (matches.isArray()) {
                            List<CompletableFuture<List<Recommendation>>> trackFutures = new ArrayList<>();
                            for (JsonNode match : matches) {
                                String simArtist = match.path("name").asText();
                                double score = match.path("match").asDouble(0.0);
                                double boost = Math.log1p(playcount) / 10.0;

                                trackFutures.add(CompletableFuture.supplyAsync(() ->
                                        getTopTracksForArtist(simArtist, artist, score + boost, 3)
                                ));
                            }
                            for (CompletableFuture<List<Recommendation>> tf : trackFutures) {
                                try {
                                    artistRecs.addAll(tf.join());
                                } catch (Exception e) {
                                    log.debug("Track future failed: {}", e.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Similar lookup failed for '{}': {}", artist, e.getMessage());
                    }
                    return artistRecs;
                }));
            }

            for (CompletableFuture<List<Recommendation>> af : artistFutures) {
                try {
                    List<Recommendation> recs = af.join();
                    for (Recommendation r : recs) {
                        if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                            if (!dedupService.alreadyDownloaded(r.getTrack().getArtist(), r.getTrack().getTitle())) {
                                all.add(r);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Artist future failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch top artists for '{}': {}", username, e.getMessage());
            return Collections.emptyList();
        }

        return rankAndLimit(all, limit);
    }
}
