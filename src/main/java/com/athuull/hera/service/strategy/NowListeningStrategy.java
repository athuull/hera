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
import java.util.concurrent.CompletableFuture;

@Component
public class NowListeningStrategy extends AbstractRecommendationStrategy {

    public NowListeningStrategy(LastFmClient lastFm, DeduplicationService dedupService) {
        super(lastFm, dedupService);
    }

    @Override
    public RecommendationStrategy getStrategy() {
        return RecommendationStrategy.NOW_LISTENING;
    }

    @Override
    public List<Recommendation> getRecommendations(RecommendationRequest request, int limit) {
        String username = request.getLastfmUsername();
        log.info("Fetching 'now listening' recommendations for '{}'", username);

        List<Recommendation> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            int recentCount = Math.min(6, Math.max(3, limit / 2));
            JsonNode recentJson = lastFm.userGetRecentTracks(username, recentCount);
            JsonNode recentTracks = recentJson.path("recenttracks").path("track");

            if (!recentTracks.isArray()) return Collections.emptyList();

            List<CompletableFuture<List<Recommendation>>> futures = new ArrayList<>();

            for (JsonNode trackNode : recentTracks) {
                String artist = trackNode.path("artist").path("name").asText(
                        trackNode.path("artist").path("#text").asText(
                                trackNode.path("artist").asText("")));
                String title = trackNode.path("name").asText();

                if (artist.isEmpty() || title.isEmpty()) continue;

                boolean nowPlaying = trackNode.has("@attr") &&
                        trackNode.path("@attr").path("nowplaying").asBoolean(false);
                if (nowPlaying) continue;

                Track scrobbledTrack = new Track(artist, title, null, null);
                seen.add(scrobbledTrack.dedupeKey());

                futures.add(CompletableFuture.supplyAsync(() -> {
                    List<Recommendation> recs = new ArrayList<>();
                    try {
                        JsonNode similar = lastFm.trackGetSimilar(artist, title, 3);
                        JsonNode similarTracks = similar.path("similartracks").path("track");

                        if (similarTracks.isArray() && !similarTracks.isEmpty()) {
                            for (JsonNode simTrack : similarTracks) {
                                String simArtist = simTrack.path("artist").path("name").asText(
                                        simTrack.path("artist").asText("Unknown"));
                                String simTitle = simTrack.path("name").asText();
                                int listeners = simTrack.path("listeners").asInt(0);
                                double match = simTrack.path("match").asDouble(0.5);

                                Track t = new Track(simArtist, simTitle, null, null);
                                recs.add(new Recommendation(t,
                                        "recently:" + artist + " - " + title,
                                        match, listeners, false, false, null));
                            }
                        } else {
                            log.debug("No track similarity for {} - {}. Falling back to artist similarity.", artist, title);
                            JsonNode similarArtists = lastFm.artistGetSimilar(artist, 3);
                            JsonNode matches = similarArtists.path("similarartists").path("artist");

                            if (matches.isArray()) {
                                for (JsonNode match : matches) {
                                    String simArtist = match.path("name").asText();
                                    double score = match.path("match").asDouble(0.5);
                                    recs.addAll(getTopTracksForArtist(simArtist, "recently:" + artist + " - " + title, score, 3));
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Track/Artist similarity failed for '{} - {}': {}", artist, title, e.getMessage());
                    }
                    return recs;
                }));
            }

            for (CompletableFuture<List<Recommendation>> f : futures) {
                try {
                    for (Recommendation r : f.join()) {
                        if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                            if (!dedupService.alreadyDownloaded(r.getTrack().getArtist(), r.getTrack().getTitle())) {
                                all.add(r);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Recent future failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch recent tracks for '{}': {}", username, e.getMessage());
            return Collections.emptyList();
        }

        log.info("Now Listening: collected {} candidates from recent tracks", all.size());
        return rankAndLimit(all, limit);
    }
}
