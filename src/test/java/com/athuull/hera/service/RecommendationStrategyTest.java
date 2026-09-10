package com.athuull.hera.service;

import com.athuull.hera.client.LastFmClient;
import com.athuull.hera.model.*;
import com.athuull.hera.service.strategy.ArtistSimilarityStrategy;
import com.athuull.hera.service.strategy.RecommendationStrategyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class RecommendationStrategyTest {

    private LastFmClient lastFmClient;
    private DeduplicationService dedupService;
    private SettingsService settingsService;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lastFmClient = Mockito.mock(LastFmClient.class);
        dedupService = Mockito.mock(DeduplicationService.class);
        settingsService = Mockito.mock(SettingsService.class);

        AppSettings appSettings = new AppSettings();
        appSettings.setMaxDailyDownloads(20);
        appSettings.setLastfmUsername("testuser");
        when(settingsService.getSettings()).thenReturn(appSettings);
    }

    @Test
    @DisplayName("ArtistSimilarityStrategy fetches similar artists and extracts top tracks")
    void testArtistSimilarityStrategy() throws Exception {
        String similarJson = """
        {
            "similarartists": {
                "artist": [
                    { "name": "Radiohead", "match": 0.95 }
                ]
            }
        }
        """;
        String topTracksJson = """
        {
            "toptracks": {
                "track": [
                    { "name": "Paranoid Android", "listeners": 1500000 },
                    { "name": "Karma Police", "listeners": 1200000 }
                ]
            }
        }
        """;

        when(lastFmClient.artistGetSimilar("Thom Yorke", 10))
                .thenReturn(objectMapper.readTree(similarJson));
        when(lastFmClient.artistGetTopTracks("Radiohead", 3))
                .thenReturn(objectMapper.readTree(topTracksJson));
        when(dedupService.alreadyDownloaded(anyString(), anyString())).thenReturn(false);

        ArtistSimilarityStrategy strategy = new ArtistSimilarityStrategy(lastFmClient, dedupService);
        RecommendationRequest request = RecommendationRequest.builder()
                .strategy(RecommendationStrategy.ARTIST_SIMILARITY)
                .seedArtists(List.of("Thom Yorke"))
                .limit(10)
                .build();

        List<Recommendation> recs = strategy.getRecommendations(request, 10);
        assertNotNull(recs);
        assertEquals(2, recs.size());
        assertEquals("Radiohead", recs.get(0).getTrack().getArtist());
        assertEquals("Paranoid Android", recs.get(0).getTrack().getTitle());
        assertTrue(recs.get(0).getMatchScore() > 0.9);
    }

    @Test
    @DisplayName("RecommendationService coordinates strategy provider correctly")
    void testRecommendationServiceRouting() {
        RecommendationStrategyProvider mockProvider = Mockito.mock(RecommendationStrategyProvider.class);
        when(mockProvider.getStrategy()).thenReturn(RecommendationStrategy.ARTIST_SIMILARITY);

        Track sampleTrack = new Track("Portishead", "Glory Box", null, null);
        Recommendation sampleRec = new Recommendation(sampleTrack, "seed", 0.9, 500000, false, false, null);
        when(mockProvider.getRecommendations(any(), eq(5))).thenReturn(List.of(sampleRec));

        RecommendationService service = new RecommendationService(
                settingsService, dedupService, List.of(mockProvider)
        );

        RecommendationRequest request = RecommendationRequest.builder()
                .strategy(RecommendationStrategy.ARTIST_SIMILARITY)
                .seedArtists(List.of("Massive Attack"))
                .limit(5)
                .build();

        List<Recommendation> results = service.getRecommendations(request);
        assertEquals(1, results.size());
        assertEquals("Portishead", results.get(0).getTrack().getArtist());
    }

    @Test
    @DisplayName("RecommendationService uses scheduledStrategy from config for scheduled runs")
    void testScheduledStrategyFromConfig() {
        AppSettings appSettings = new AppSettings();
        appSettings.setLastfmUsername("athul");
        appSettings.setMaxDailyDownloads(25);
        appSettings.setScheduledStrategy(RecommendationStrategy.NOW_LISTENING);
        when(settingsService.getSettings()).thenReturn(appSettings);

        RecommendationStrategyProvider nowListeningProvider = Mockito.mock(RecommendationStrategyProvider.class);
        when(nowListeningProvider.getStrategy()).thenReturn(RecommendationStrategy.NOW_LISTENING);

        Track track = new Track("Boards of Canada", "Dayvan Cowboy", null, null);
        Recommendation rec = new Recommendation(track, "recent", 0.95, 400000, false, false, null);
        when(nowListeningProvider.getRecommendations(any(), eq(25))).thenReturn(List.of(rec));

        RecommendationService service = new RecommendationService(
                settingsService, dedupService, List.of(nowListeningProvider)
        );

        List<Recommendation> results = service.getRecommendationsFromConfig();
        assertEquals(1, results.size());
        assertEquals("Boards of Canada", results.get(0).getTrack().getArtist());
        assertEquals("Dayvan Cowboy", results.get(0).getTrack().getTitle());
    }
}
