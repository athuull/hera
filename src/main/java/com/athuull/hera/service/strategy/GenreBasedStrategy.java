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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Derives the user's top musical genres by inspecting tags of their favorite artists,
 * then samples top tracks from those genres.
 * <p>
 * <b>Algorithm:</b>
 * <ol>
 *   <li>Fetches the user's top artists over recent months.</li>
 *   <li>Queries {@code artist.getTopTags} for each artist concurrently, scoring tags weighted by play count.</li>
 *   <li>Filters out noise tags (e.g. "seen live", "favorites") and selects the top 3 dominant genres.</li>
 *   <li>Queries {@code tag.getTopTracks} for each dominant genre to discover tracks.</li>
 * </ol>
 */
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

        Map<String, Integer> genreScores = new ConcurrentHashMap<>();

        try {
            int topArtistCount = Math.min(5, Math.max(3, (int) Math.ceil(limit / 5.0)));
            JsonNode topArtists = lastFm.userGetTopArtists(username, "3month", topArtistCount);
            JsonNode artistNodes = topArtists.path("topartists").path("artist");
            if (!artistNodes.isArray() || artistNodes.isEmpty()) return Collections.emptyList();

            List<CompletableFuture<Void>> tagFutures = new ArrayList<>();

            for (JsonNode artistNode : artistNodes) {
                String artist = artistNode.path("name").asText();
                int playcount = artistNode.path("playcount").asInt(1);

                tagFutures.add(CompletableFuture.runAsync(() -> {
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
                }));
            }

            CompletableFuture.allOf(tagFutures.toArray(new CompletableFuture[0])).join();

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
        int tracksPerTag = Math.max(2, (limit / tagsToUse) + 1);

        List<CompletableFuture<List<Recommendation>>> genreTrackFutures = new ArrayList<>();

        for (String tag : topGenres) {
            genreTrackFutures.add(CompletableFuture.supplyAsync(() -> {
                List<Recommendation> tagRecs = new ArrayList<>();
                try {
                    JsonNode topTracks = lastFm.tagGetTopTracks(tag, tracksPerTag);
                    JsonNode tracks = topTracks.path("tracks").path("track");

                    if (tracks.isArray()) {
                        for (JsonNode track : tracks) {
                            String artist = track.path("artist").path("name").asText("Unknown");
                            String title = track.path("name").asText();

                            Track t = new Track(artist, title, null, null);
                            tagRecs.add(new Recommendation(t, "genre:" + tag, 0.7, 0, false, false, null));
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to fetch top tracks for tag '{}': {}", tag, e.getMessage());
                }
                return tagRecs;
            }));
        }

        for (CompletableFuture<List<Recommendation>> gf : genreTrackFutures) {
            try {
                for (Recommendation r : gf.join()) {
                    if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                        if (!dedupService.alreadyDownloaded(r.getTrack().getArtist(), r.getTrack().getTitle())) {
                            all.add(r);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Genre track future failed: {}", e.getMessage());
            }
        }

        log.info("Genre Based: collected {} candidate tracks", all.size());
        return rankAndLimit(all, limit);
    }
}
