package com.athuull.hera.service.strategy;

import com.athuull.hera.client.LastFmClient;
import com.athuull.hera.model.Recommendation;
import com.athuull.hera.model.Track;
import com.athuull.hera.service.DeduplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Base template class for recommendation strategy providers.
 * <p>
 * Implements the <b>Template Method Pattern</b> by supplying common utilities:
 * <ul>
 *   <li><b>Logarithmic Popularity Weighting:</b> Multiplies match scores by {@code ln(1 + listeners)}
 *       to balance niche similarity with track quality/popularity.</li>
 *   <li><b>Recent Scrobbles Extraction:</b> Pre-loads recently played tracks from Last.fm
 *       to ensure users are not recommended music they listened to hours earlier.</li>
 *   <li><b>Safe Top Track Lookups:</b> Normalizes artist names and handles Last.fm API anomalies.</li>
 * </ul>
 */
public abstract class AbstractRecommendationStrategy implements RecommendationStrategyProvider {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final LastFmClient lastFm;
    protected final DeduplicationService dedupService;

    protected AbstractRecommendationStrategy(LastFmClient lastFm, DeduplicationService dedupService) {
        this.lastFm = lastFm;
        this.dedupService = dedupService;
    }

    protected List<Recommendation> rankAndLimit(List<Recommendation> recs, int limit) {
        if (recs == null || recs.isEmpty()) {
            return Collections.emptyList();
        }
        recs.sort((a, b) -> {
            double scoreA = a.getMatchScore() * (a.getListenerCount() > 0 ? Math.log1p(a.getListenerCount()) : 1);
            double scoreB = b.getMatchScore() * (b.getListenerCount() > 0 ? Math.log1p(b.getListenerCount()) : 1);
            return Double.compare(scoreB, scoreA);
        });
        int effectiveLimit = Math.min(limit, recs.size());
        return new ArrayList<>(recs.subList(0, effectiveLimit));
    }

    protected Set<String> getRecentlyPlayedKeys(String username, int count) {
        try {
            JsonNode recentTracks = lastFm.userGetRecentTracks(username, count);
            return extractTrackKeys(recentTracks, "recenttracks");
        } catch (Exception e) {
            log.warn("Could not fetch recent tracks for dedup: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    protected List<Recommendation> getTopTracksForArtist(String artist, String source, double matchScore, int limit) {
        List<Recommendation> result = new ArrayList<>();
        try {
            JsonNode topTracks = lastFm.artistGetTopTracks(artist, limit > 0 ? limit : 3);
            JsonNode tracks = topTracks.path("toptracks").path("track");
            if (!tracks.isArray()) return result;

            for (JsonNode track : tracks) {
                String title = track.path("name").asText();
                int listeners = track.path("listeners").asInt(0);

                String trackArtist = track.path("artist").path("name").asText(
                        track.path("artist").path("#text").asText(
                                track.path("artist").asText(artist)));
                if (trackArtist == null || trackArtist.isBlank()) {
                    trackArtist = artist;
                }

                Track t = new Track(trackArtist, title, null, null);
                result.add(new Recommendation(t, source, matchScore, listeners, false, false, null));
            }
        } catch (Exception e) {
            log.debug("Top tracks lookup failed for '{}': {}", artist, e.getMessage());
        }
        return result;
    }

    protected void extractTopTracks(String artist, String source, double matchScore,
                                   Set<String> seen, List<Recommendation> recs) {
        List<Recommendation> tracks = getTopTracksForArtist(artist, source, matchScore, 3);
        for (Recommendation r : tracks) {
            if (r.getTrack() != null && seen.add(r.getTrack().dedupeKey())) {
                if (!dedupService.alreadyDownloaded(r.getTrack().getArtist(), r.getTrack().getTitle()) &&
                    !dedupService.alreadyDownloaded(artist, r.getTrack().getTitle())) {
                    recs.add(r);
                }
            }
        }
    }

    protected Set<String> extractTrackKeys(JsonNode response, String rootKey) {
        Set<String> keys = new HashSet<>();
        JsonNode tracks = response.path(rootKey).path("track");
        if (tracks.isArray()) {
            for (JsonNode track : tracks) {
                String artist = track.path("artist").path("name").asText(
                        track.path("artist").path("#text").asText(
                                track.path("artist").asText("Unknown")));
                String title = track.path("name").asText();

                Track t = new Track(artist, title, null, null);
                keys.add(t.dedupeKey());
            }
        }
        return keys;
    }
}
