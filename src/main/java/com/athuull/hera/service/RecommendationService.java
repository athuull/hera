package com.athuull.hera.service;

import com.athuull.hera.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.athuull.hera.client.LastFmClient;
import com.athuull.hera.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private final LastFmClient lastFm;
    private final SettingsService settingsService;
    private final DeduplicationService dedupService;

    @Autowired
    public RecommendationService(LastFmClient lastFm, SettingsService settingsService, DeduplicationService dedupService) {
        this.lastFm = lastFm;
        this.settingsService = settingsService;
        this.dedupService = dedupService;
    }

    public List<Recommendation> getRecommendations(RecommendationRequest request) {
        AppSettings appSettings = settingsService.getSettings();

        int limit = request.getLimit() != null ? request.getLimit() : appSettings.getMaxDailyDownloads();
        limit = Math.min(limit, appSettings.getMaxDailyDownloads());

        RecommendationStrategy strategy = request.getStrategy();
        if (strategy == null) strategy = RecommendationStrategy.USER_PERSONALIZED;

        String username = (request.getLastfmUsername() != null && !request.getLastfmUsername().isBlank()) ?
                request.getLastfmUsername() : appSettings.getLastfmUsername();

        if (requiresUsername(strategy) && (username == null || username.isBlank())) {
            log.error("Strategy {} requires a Last.fm username but none is configured", strategy);
            return Collections.emptyList();
        }

        log.info("Generating recommendations via {} strategy for user '{}', limit={}", strategy, username, limit);

        // Refresh the hard drive index ONCE at the beginning
        dedupService.refreshIndex();

        List<Recommendation> recs = switch (strategy) {
            case USER_PERSONALIZED -> userPersonalizedRecs(username,
                    request.getPeriod() != null ? request.getPeriod() : "overall", limit);
            case NOW_LISTENING -> nowListeningRecs(username, limit);
            case GENRE_BASED -> genreBasedRecs(username, limit);
            case ARTIST_SIMILARITY -> artistSimilarityRecs(request.getSeedArtists(), limit);
            case TRACK_SIMILARITY -> trackSimilarityRecs(request.getSeedArtist(), request.getSeedTrack(), limit);
            case TAG_BASED -> tagBasedRecs(request.getTag(), limit);
            case HYBRID -> hybridRecs(username, limit);
        };

        log.info("Generated {} recommendations", recs.size());
        return recs;
    }

    public List<Recommendation> getRecommendations(String seedArtist, int limit) {
        return getRecommendations(RecommendationRequest.builder()
                .strategy(RecommendationStrategy.ARTIST_SIMILARITY)
                .seedArtists(List.of(seedArtist))
                .limit(limit).build());
    }

    public List<Recommendation> getRecommendationsFromConfig() {
        AppSettings appSettings = settingsService.getSettings();
        if (appSettings.getLastfmUsername() == null || appSettings.getLastfmUsername().isBlank()) {
            log.error("No Last.fm username configured — scheduled pipeline cannot run. Set it in the WebUI.");
            return Collections.emptyList();
        }

        return getRecommendations(RecommendationRequest.builder()
                .strategy(RecommendationStrategy.HYBRID)
                .lastfmUsername(appSettings.getLastfmUsername())
                .limit(appSettings.getMaxDailyDownloads())
                .build());
    }

    private boolean requiresUsername(RecommendationStrategy strategy) {
        return strategy == RecommendationStrategy.USER_PERSONALIZED ||
                strategy == RecommendationStrategy.NOW_LISTENING ||
                strategy == RecommendationStrategy.GENRE_BASED ||
                strategy == RecommendationStrategy.HYBRID;
    }

    // ═══ STRATEGY: User Personalized ═══

    private List<Recommendation> userPersonalizedRecs(String username, String period, int limit) {
        log.info("Fetching personalized recommendations for '{}', period='{}'", username, period);

        List<Recommendation> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Set<String> recentlyPlayed = getRecentlyPlayedKeys(username, 50);
        seen.addAll(recentlyPlayed);
        log.info("Loaded {} recently played tracks for dedup", recentlyPlayed.size());

        JsonNode topArtists = lastFm.userGetTopArtists(username, period, 8);
        JsonNode artistNodes = topArtists.path("topartists").path("artist");
        if (!artistNodes.isArray()) return Collections.emptyList();

        for (JsonNode artistNode : artistNodes) {
            String artist = artistNode.path("name").asText();
            int playcount = artistNode.path("playcount").asInt(0);

            try {
                JsonNode similar = lastFm.artistGetSimilar(artist, 5);
                JsonNode matches = similar.path("similarartists").path("artist");

                if (matches.isArray()) {
                    for (JsonNode match : matches) {
                        String simArtist = match.path("name").asText();
                        double score = match.path("match").asDouble(0.0);
                        double boost = Math.log1p(playcount) / 10.0;
                        extractTopTracks(simArtist, artist, score + boost, seen, all);
                    }
                }
            } catch (Exception e) {
                log.debug("Similar lookup failed for '{}': {}", artist, e.getMessage());
            }
        }

        return rankAndLimit(all, limit);
    }

    // ═══ STRATEGY: Now Listening (WITH ARTIST FALLBACK) ═══

    private List<Recommendation> nowListeningRecs(String username, int limit) {
        log.info("Fetching 'now listening' recommendations for '{}'", username);

        List<Recommendation> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        JsonNode recentJson = lastFm.userGetRecentTracks(username, 10);
        JsonNode recentTracks = recentJson.path("recenttracks").path("track");

        if (!recentTracks.isArray()) return Collections.emptyList();

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
                        if (seen.add(t.dedupeKey())) {
                            if (!dedupService.alreadyDownloaded(simArtist, simTitle)) {
                                all.add(new Recommendation(t,
                                        "recently:" + artist + " - " + title,
                                        match, listeners, false, false, null));
                            }
                        }
                    }
                } else {
                    log.debug("No track similarity for {} - {}. Falling back to artist similarity.", artist, title);
                    JsonNode similarArtists = lastFm.artistGetSimilar(artist, 3);
                    JsonNode matches = similarArtists.path("similarartists").path("artist");

                    if (matches.isArray()) {
                        for (JsonNode match : matches) {
                            String simArtist = match.path("name").asText();
                            double score = match.path("match").asDouble(0.5);
                            extractTopTracks(simArtist, "recently:" + artist + " - " + title, score, seen, all);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Track/Artist similarity failed for '{} - {}': {}", artist, title, e.getMessage());
            }
        }

        log.info("Now Listening: collected {} candidates from 10 recent tracks", all.size());
        return rankAndLimit(all, limit);
    }

    // ═══ STRATEGY: Genre Based ═══

    private List<Recommendation> genreBasedRecs(String username, int limit) {
        log.info("Fetching genre-based recommendations for '{}'", username);

        List<Recommendation> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Set<String> recentlyPlayed = getRecentlyPlayedKeys(username, 50);
        seen.addAll(recentlyPlayed);

        JsonNode topArtists = lastFm.userGetTopArtists(username, "3month", 10);
        JsonNode artistNodes = topArtists.path("topartists").path("artist");
        if (!artistNodes.isArray() || artistNodes.isEmpty()) return Collections.emptyList();

        Map<String, Integer> genreScores = new HashMap<>();
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

    // ═══ STRATEGY: Hybrid ═══

    private List<Recommendation> hybridRecs(String username, int limit) {
        List<Recommendation> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int nowListeningQuota = (int) (limit * 0.4);
        List<Recommendation> nowRecs = nowListeningRecs(username, nowListeningQuota);
        for (Recommendation r : nowRecs) {
            if (seen.add(r.getTrack().dedupeKey())) all.add(r);
        }
        log.info("Hybrid: {} recs from Now Listening", all.size());

        int personalizedQuota = (int) (limit * 0.35);
        List<Recommendation> userRecs = userPersonalizedRecs(username, "3month", personalizedQuota);
        for (Recommendation r : userRecs) {
            if (seen.add(r.getTrack().dedupeKey())) all.add(r);
        }
        log.info("Hybrid: {} total after User Personalized", all.size());

        int genreQuota = limit - all.size();
        if (genreQuota > 0) {
            List<Recommendation> genreRecs = genreBasedRecs(username, genreQuota);
            for (Recommendation r : genreRecs) {
                if (seen.add(r.getTrack().dedupeKey())) all.add(r);
            }
            log.info("Hybrid: {} total after Genre Based", all.size());
        }

        return rankAndLimit(all, limit);
    }

    // ═══ MANUAL STRATEGIES ═══

    private List<Recommendation> artistSimilarityRecs(List<String> seedArtists, int limit) {
        if (seedArtists == null || seedArtists.isEmpty()) return Collections.emptyList();

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

    private List<Recommendation> trackSimilarityRecs(String seedArtist, String seedTrack, int limit) {
        if (seedArtist == null || seedTrack == null) return Collections.emptyList();

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

    private List<Recommendation> tagBasedRecs(String tag, int limit) {
        if (tag == null || tag.isBlank()) return Collections.emptyList();

        List<Recommendation> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            JsonNode topTracks = lastFm.tagGetTopTracks(tag, limit * 2);
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

    // ═══ HELPERS ═══

    private Set<String> getRecentlyPlayedKeys(String username, int count) {
        try {
            JsonNode recentTracks = lastFm.userGetRecentTracks(username, count);
            return extractTrackKeys(recentTracks, "recenttracks");
        } catch (Exception e) {
            log.warn("Could not fetch recent tracks for dedup: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    private void extractTopTracks(String artist, String source, double matchScore,
                                  Set<String> seen, List<Recommendation> recs) {
        try {
            JsonNode topTracks = lastFm.artistGetTopTracks(artist, 3);
            JsonNode tracks = topTracks.path("toptracks").path("track");
            if (!tracks.isArray()) return;

            for (JsonNode track : tracks) {
                String title = track.path("name").asText();
                int listeners = track.path("listeners").asInt(0);

                Track t = new Track(artist, title, null, null);
                if (seen.add(t.dedupeKey())) {
                    if (!dedupService.alreadyDownloaded(artist, title)) {
                        recs.add(new Recommendation(t, source, matchScore, listeners, false, false, null));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Top tracks lookup failed for '{}': {}", artist, e.getMessage());
        }
    }

    private Set<String> extractTrackKeys(JsonNode response, String rootKey) {
        Set<String> keys = new HashSet<>();
        JsonNode tracks = response.path(rootKey).path("track");
        if (tracks.isArray()) {
            for (JsonNode track : tracks) {
                // FIX: Last.fm API returns artist under "name" when extended=1, and "#text" otherwise. Check both!
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

    private List<Recommendation> rankAndLimit(List<Recommendation> recs, int limit) {
        recs.sort((a, b) -> {
            double scoreA = a.getMatchScore() * (a.getListenerCount() > 0 ? Math.log1p(a.getListenerCount()) : 1);
            double scoreB = b.getMatchScore() * (b.getListenerCount() > 0 ? Math.log1p(b.getListenerCount()) : 1);
            return Double.compare(scoreB, scoreA);
        });
        int effectiveLimit = Math.min(limit, recs.size());
        return recs.subList(0, effectiveLimit);
    }
}