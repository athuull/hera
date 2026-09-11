package com.athuull.hera.service;

import com.athuull.hera.model.AppSettings;
import com.athuull.hera.model.DownloadResult;
import com.athuull.hera.model.Track;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadServiceTest {

    private DownloadService downloadService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        downloadService = Mockito.mock(DownloadService.class, Mockito.CALLS_REAL_METHODS);
    }

    // ─── cleanForComparison ───────────────────────────────────────────────────

    @Test
    @DisplayName("cleanForComparison strips parenthesised feat list and lowercases")
    void testCleanStripsParensFeat() {
        assertEquals("raf", downloadService.cleanForComparison("RAF (feat. A$AP Rocky, Playboi Carti, Quavo & Lil Uzi Vert)"));
        assertEquals("raf", downloadService.cleanForComparison("Raf (feat. A$AP Rocky, Playboi Carti, Quavo, Lil Uzi Vert & Frank Ocean)"));
    }

    @Test
    @DisplayName("cleanForComparison strips bracket annotations like [Radio Edit]")
    void testCleanStripsBrackets() {
        assertEquals("say it", downloadService.cleanForComparison("Say It [Radio Edit]"));
    }

    @Test
    @DisplayName("cleanForComparison strips bare feat. not in parens")
    void testCleanStripsBareFeature() {
        assertEquals("humble.", downloadService.cleanForComparison("HUMBLE. feat. SomeArtist"));
    }

    @Test
    @DisplayName("cleanForComparison handles null safely")
    void testCleanNull() {
        assertEquals("", downloadService.cleanForComparison(null));
    }

    @Test
    @DisplayName("cleanForComparison preserves core chars like $ outside parens")
    void testCleanPreservesSpecialChars() {
        assertEquals("a$ap mob", downloadService.cleanForComparison("A$AP Mob"));
    }

    // ─── isPlausibleMatch ─────────────────────────────────────────────────────

    @Test
    @DisplayName("isPlausibleMatch accepts match when feat list differs but core title matches")
    void testPlausibleMatchFeatVariant() {
        Track requested = new Track("A$AP Mob",
                "Raf (feat. A$AP Rocky, Playboi Carti, Quavo, Lil Uzi Vert & Frank Ocean)", null, null);
        assertTrue(downloadService.isPlausibleMatch(requested, "A$AP Mob",
                "RAF (feat. A$AP Rocky, Playboi Carti, Quavo & Lil Uzi Vert)"));
    }

    @Test
    @DisplayName("isPlausibleMatch accepts match when title case differs")
    void testPlausibleMatchCaseInsensitive() {
        Track requested = new Track("Tory Lanez", "Say It", null, null);
        assertTrue(downloadService.isPlausibleMatch(requested, "Tory Lanez", "SAY IT"));
    }

    @Test
    @DisplayName("isPlausibleMatch rejects completely different title")
    void testPlausibleMatchRejectsDifferentTitle() {
        Track requested = new Track("Tory Lanez", "Say It", null, null);
        assertFalse(downloadService.isPlausibleMatch(requested, "Tory Lanez",
                "The Take (feat. Chris Brown)"));
    }

    @Test
    @DisplayName("isPlausibleMatch rejects null matchedTitle")
    void testPlausibleMatchNullTitle() {
        Track requested = new Track("Artist", "Title", null, null);
        assertFalse(downloadService.isPlausibleMatch(requested, "Artist", null));
    }

    @Test
    @DisplayName("isPlausibleMatch passes when matched artist string is blank")
    void testPlausibleMatchBlankArtist() {
        Track requested = new Track("Artist", "Song", null, null);
        assertTrue(downloadService.isPlausibleMatch(requested, "", "Song"));
    }


    @Test
    @DisplayName("isPlausibleMatch REJECTS 'Say It (Skit)' when requesting 'Say It'")
    void testPlausibleMatchRejectsSkit() {
        // cleanTitle("Say It (Skit)") = "say it (skit)" — skit is preserved, not stripped
        // cleanTitle("Say It") = "say it"
        // "say it (skit)" != "say it" → rejected
        Track requested = new Track("Tory Lanez", "Say It", null, null);
        assertFalse(downloadService.isPlausibleMatch(requested, "Tory Lanez", "Say It (Skit)"),
                "Should reject 'Say It (Skit)' when 'Say It' was requested");
    }

    @Test
    @DisplayName("isPlausibleMatch REJECTS 'Lanez' when requesting 'Tory Lanez' — partial name is not enough")
    void testPlausibleMatchRejectsPartialArtistName() {
        Track requested = new Track("Tory Lanez", "Say It", null, null);
        assertFalse(downloadService.isPlausibleMatch(requested, "Lanez", "Say It"),
                "Should reject 'Lanez' as a match for 'Tory Lanez' — different artist");
    }

    @Test
    @DisplayName("isPlausibleMatch accepts when matched artist contains full requested name (collaborative credit)")
    void testPlausibleMatchGotArtistContainsReqArtist() {
        Track requested = new Track("Tory Lanez", "Say It", null, null);
        assertTrue(downloadService.isPlausibleMatch(requested, "Tory Lanez & Bryson Tiller", "Say It"));
    }

    @Test
    @DisplayName("isPlausibleMatch accepts semicolon-separated artists matching primary artist (e.g. Tory Lanez; Tee)")
    void testPlausibleMatchSemicolonPrimaryArtist() {
        Track requested = new Track("Tory Lanez; Tee", "Pink Dolphin Sunset (feat. Tee)", null, null);
        assertTrue(downloadService.isPlausibleMatch(requested, "Tory Lanez", "Pink Dolphin Sunset"),
                "Should match primary artist when requested has semicolon-separated artists");
    }

    @Test
    @DisplayName("isPlausibleMatch accepts semicolon-separated artists matching collaborative credit")
    void testPlausibleMatchSemicolonCollabArtist() {
        Track requested = new Track("BoyWithUke; blackbear", "IDGAF", null, null);
        assertTrue(downloadService.isPlausibleMatch(requested, "BoyWithUke", "IDGAF (feat. blackbear)"),
                "Should match primary artist and stripped feat title");
    }

    @Test
    @DisplayName("isPlausibleMatch rejects different artist even with semicolon requested artists")
    void testPlausibleMatchSemicolonRejectsWrongArtist() {
        Track requested = new Track("Tory Lanez; Tee", "Pink Dolphin Sunset", null, null);
        assertFalse(downloadService.isPlausibleMatch(requested, "Lanez", "Pink Dolphin Sunset"),
                "Should still reject 'Lanez' even when multiple artists are requested");
    }


    // ─── searchForTrack ───────────────────────────────────────────────────────

    @Test
    @DisplayName("searchForTrack skips wrong songs and skit, returns the real 'Say It' (real log scenario)")
    void testSearchForTrackSkipsBadResults() throws Exception {
        // Mirrors the actual YouTube Music result order from logs:
        // positions 1-6 are wrong songs, 7 is "Say It (Skit)", 8 is the real "Say It"
        String resultsJson = "[" +
            "{\"name\":\"The Take (feat. Chris Brown)\",\"artists\":[\"Tory Lanez\"]}," +
            "{\"name\":\"The Color Violet\",\"artists\":[\"Tory Lanez\"]}," +
            "{\"name\":\"In For It (feat. RL Grime)\",\"artists\":[\"Tory Lanez\"]}," +
            "{\"name\":\"Traphouse\",\"artists\":[\"Tory Lanez\"]}," +
            "{\"name\":\"TAlk tO Me\",\"artists\":[\"Tory Lanez\"]}," +
            "{\"name\":\"Hate To Say\",\"artists\":[\"Tory Lanez\"]}," +
            "{\"name\":\"Say It (Skit)\",\"artists\":[\"Tory Lanez\"]}," +
            "{\"name\":\"Say It\",\"artists\":[\"Tory Lanez\"]}" +
            "]";
        JsonNode fakeResults = mapper.readTree(resultsJson);

        com.athuull.hera.client.DowntifyClient fakeClient =
                Mockito.mock(com.athuull.hera.client.DowntifyClient.class);
        when(fakeClient.searchSongs(anyString())).thenReturn(fakeResults);

        DownloadService svc = new DownloadService(
                fakeClient,
                Mockito.mock(com.athuull.hera.config.DowntifyConfig.class),
                Mockito.mock(SettingsService.class),
                Mockito.mock(DeduplicationService.class),
                Mockito.mock(FormatCleanupService.class),
                Mockito.mock(com.athuull.hera.ws.ProgressWebSocketHandler.class)
        );

        Track requested = new Track("Tory Lanez", "Say It", null, null);
        Optional<JsonNode> result = svc.searchForTrack(requested);

        assertTrue(result.isPresent(), "Expected a result at position 7");
        assertEquals("Say It", result.get().path("name").asText());
    }

    @Test
    @DisplayName("searchForTrack returns empty when all results are mismatches")
    void testSearchForTrackReturnsEmptyWhenNoMatch() throws Exception {
        String resultsJson = "[" +
            "{\"name\":\"Random Song 1\",\"artists\":[\"Someone Else\"]}," +
            "{\"name\":\"Random Song 2\",\"artists\":[\"Another Artist\"]}" +
            "]";
        JsonNode fakeResults = mapper.readTree(resultsJson);

        com.athuull.hera.client.DowntifyClient fakeClient =
                Mockito.mock(com.athuull.hera.client.DowntifyClient.class);
        when(fakeClient.searchSongs(anyString())).thenReturn(fakeResults);

        DownloadService svc = new DownloadService(
                fakeClient,
                Mockito.mock(com.athuull.hera.config.DowntifyConfig.class),
                Mockito.mock(SettingsService.class),
                Mockito.mock(DeduplicationService.class),
                Mockito.mock(FormatCleanupService.class),
                Mockito.mock(com.athuull.hera.ws.ProgressWebSocketHandler.class)
        );

        Track requested = new Track("Tory Lanez", "Say It", null, null);
        Optional<JsonNode> result = svc.searchForTrack(requested);

        assertTrue(result.isEmpty(), "Expected no match");
    }

    // ─── extractArtistName ────────────────────────────────────────────────────

    @Test
    @DisplayName("extractArtistName handles plain-string artists array element")
    void testExtractArtistNameFromString() throws Exception {
        // Some Downtify versions return: "artists": ["Tory Lanez"]
        JsonNode stringNode = mapper.readTree("\"Tory Lanez\"");
        assertEquals("Tory Lanez", downloadService.extractArtistName(stringNode));
    }

    @Test
    @DisplayName("extractArtistName handles object artists array element (ytmusicapi format)")
    void testExtractArtistNameFromObject() throws Exception {
        // ytmusicapi returns: "artists": [{"name": "Tory Lanez", "id": "UCxxx"}]
        JsonNode objectNode = mapper.readTree("{\"name\": \"Tory Lanez\", \"id\": \"UCxxx\"}");
        assertEquals("Tory Lanez", downloadService.extractArtistName(objectNode));
    }

    @Test
    @DisplayName("extractArtistName returns empty string for null")
    void testExtractArtistNameNull() {
        assertEquals("", downloadService.extractArtistName(null));
    }

    @Test
    @DisplayName("searchForTrack correctly identifies artist when artists array contains objects")
    void testSearchForTrackWithObjectArtists() throws Exception {
        // ytmusicapi object-format artists array
        String resultsJson = "[" +
            "{\"name\":\"Say It\",\"artists\":[{\"name\":\"Tory Lanez\",\"id\":\"UCxxx\"}]}" +
            "]";
        JsonNode fakeResults = mapper.readTree(resultsJson);

        com.athuull.hera.client.DowntifyClient fakeClient =
                Mockito.mock(com.athuull.hera.client.DowntifyClient.class);
        when(fakeClient.searchSongs(anyString())).thenReturn(fakeResults);

        DownloadService svc = new DownloadService(
                fakeClient,
                Mockito.mock(com.athuull.hera.config.DowntifyConfig.class),
                Mockito.mock(SettingsService.class),
                Mockito.mock(DeduplicationService.class),
                Mockito.mock(FormatCleanupService.class),
                Mockito.mock(com.athuull.hera.ws.ProgressWebSocketHandler.class)
        );

        Track requested = new Track("Tory Lanez", "Say It", null, null);
        Optional<JsonNode> result = svc.searchForTrack(requested);

        assertTrue(result.isPresent(), "Expected match to be found with object-format artist");
        assertEquals("Say It", result.get().path("name").asText());
    }

    // ─── downloadBatch ────────────────────────────────────────────────────────

    @Test
    @DisplayName("downloadBatch counts notFound tracks as failed in batch completion")
    void testDownloadBatchCountsNotFoundAsFailed() throws Exception {
        com.athuull.hera.client.DowntifyClient fakeClient =
                Mockito.mock(com.athuull.hera.client.DowntifyClient.class);
        com.athuull.hera.ws.ProgressWebSocketHandler fakeHandler =
                Mockito.mock(com.athuull.hera.ws.ProgressWebSocketHandler.class);
        DeduplicationService fakeDedup = Mockito.mock(DeduplicationService.class);
        com.athuull.hera.config.DowntifyConfig fakeConfig =
                Mockito.mock(com.athuull.hera.config.DowntifyConfig.class);
        when(fakeConfig.getPollTimeoutMs()).thenReturn(1000L);
        when(fakeConfig.getPollIntervalMs()).thenReturn(10L);

        // Track 1 fails search (no matches on YouTube Music)
        Track trackFail = new Track("Tory Lanez", "Say It", null, null);
        when(fakeClient.searchSongs(trackFail.toSearchQuery())).thenReturn(mapper.readTree("[]"));

        // Track 2 succeeds search
        Track trackOk = new Track("Artist2", "Song2", null, null);
        String song2Json = "[{\"name\":\"Song2\",\"artists\":[\"Artist2\"]}]";
        when(fakeClient.searchSongs(trackOk.toSearchQuery())).thenReturn(mapper.readTree(song2Json));

        // Downtify download batch returns count: 1
        when(fakeClient.downloadBatch(anyList())).thenReturn(mapper.readTree("{\"count\": 1}"));

        // Downtify queue returns 1 completed job
        String queueJson = "[{\"status\":\"done\",\"filename\":\"Artist2 - Song2.mp3\",\"name\":\"Song2\",\"artists\":[\"Artist2\"]}]";
        when(fakeClient.getQueue()).thenReturn(mapper.readTree(queueJson));

        DownloadService svc = new DownloadService(
                fakeClient,
                fakeConfig,
                Mockito.mock(SettingsService.class),
                fakeDedup,
                Mockito.mock(FormatCleanupService.class),
                fakeHandler
        );

        List<DownloadResult> results = svc.downloadBatch(List.of(trackFail, trackOk));

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> r.isError() && "no match found on youtube music".equals(r.getErrorMessage())));
        assertTrue(results.stream().anyMatch(DownloadResult::isDone));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(fakeHandler, atLeastOnce()).broadcast(captor.capture());

        JsonNode completePayload = null;
        for (String msg : captor.getAllValues()) {
            JsonNode node = mapper.readTree(msg);
            if ("batch_complete".equals(node.path("type").asText())) {
                completePayload = node;
            }
        }

        assertNotNull(completePayload, "batch_complete payload must be broadcast");
        assertEquals(2, completePayload.path("total").asInt());
        assertEquals(1, completePayload.path("downloaded").asInt());
        assertEquals(1, completePayload.path("failed").asInt(), "failed count must be 1 because 1 track was not found");
        assertEquals(0, completePayload.path("skipped").asInt());
    }

    @Test
    @DisplayName("downloadBatch handles batch where all tracks are skipped or not found")
    void testDownloadBatchAllSkippedOrNotFound() throws Exception {
        com.athuull.hera.client.DowntifyClient fakeClient =
                Mockito.mock(com.athuull.hera.client.DowntifyClient.class);
        com.athuull.hera.ws.ProgressWebSocketHandler fakeHandler =
                Mockito.mock(com.athuull.hera.ws.ProgressWebSocketHandler.class);
        DeduplicationService fakeDedup = Mockito.mock(DeduplicationService.class);
        com.athuull.hera.config.DowntifyConfig fakeConfig =
                Mockito.mock(com.athuull.hera.config.DowntifyConfig.class);

        Track trackSkipped = new Track("Artist1", "AlreadyHave", null, null);
        when(fakeDedup.alreadyDownloaded("Artist1", "AlreadyHave")).thenReturn(true);

        Track trackNotFound = new Track("Artist2", "NotFound", null, null);
        when(fakeDedup.alreadyDownloaded("Artist2", "NotFound")).thenReturn(false);
        when(fakeClient.searchSongs(trackNotFound.toSearchQuery())).thenReturn(mapper.readTree("[]"));

        DownloadService svc = new DownloadService(
                fakeClient,
                fakeConfig,
                Mockito.mock(SettingsService.class),
                fakeDedup,
                Mockito.mock(FormatCleanupService.class),
                fakeHandler
        );

        List<DownloadResult> results = svc.downloadBatch(List.of(trackSkipped, trackNotFound));

        assertEquals(2, results.size());
        assertEquals(1, results.stream().filter(r -> "skipped".equals(r.getStatus())).count());
        assertEquals(1, results.stream().filter(DownloadResult::isError).count());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(fakeHandler, atLeastOnce()).broadcast(captor.capture());

        JsonNode completePayload = null;
        for (String msg : captor.getAllValues()) {
            JsonNode node = mapper.readTree(msg);
            if ("batch_complete".equals(node.path("type").asText())) {
                completePayload = node;
            }
        }

        assertNotNull(completePayload);
        assertEquals(2, completePayload.path("total").asInt());
        assertEquals(0, completePayload.path("downloaded").asInt());
        assertEquals(1, completePayload.path("failed").asInt());
        assertEquals(1, completePayload.path("skipped").asInt());
    }

    @Test
    @DisplayName("configureDowntify pushes download_cover_art and cover_resolution")
    void testConfigureDowntifyIncludesCoverArtSettings() {
        com.athuull.hera.client.DowntifyClient fakeClient =
                Mockito.mock(com.athuull.hera.client.DowntifyClient.class);
        SettingsService fakeSettingsService = Mockito.mock(SettingsService.class);
        AppSettings settings = AppSettings.builder()
                .format("mp3")
                .bitrate("320")
                .organizeByArtist(true)
                .downloadLyrics(true)
                .downloadCoverArt(true)
                .coverResolution(800)
                .build();
        when(fakeSettingsService.getSettings()).thenReturn(settings);

        DownloadService svc = new DownloadService(
                fakeClient,
                Mockito.mock(com.athuull.hera.config.DowntifyConfig.class),
                fakeSettingsService,
                Mockito.mock(DeduplicationService.class),
                Mockito.mock(FormatCleanupService.class),
                Mockito.mock(com.athuull.hera.ws.ProgressWebSocketHandler.class)
        );

        svc.configureDowntify();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fakeClient).updateSettings(captor.capture());

        Map<String, Object> sent = captor.getValue();
        assertEquals(true, sent.get("download_cover_art"));
        assertEquals(800, sent.get("cover_resolution"));
        assertEquals("mp3", sent.get("format"));
        assertEquals("320", sent.get("bitrate"));
    }

    @Test
    @DisplayName("downloadUrlOrAlbum routes YouTube Music album to downloadAlbum")
    void testDownloadUrlOrAlbumWithYtMusicAlbum() {
        com.athuull.hera.client.DowntifyClient fakeClient =
                Mockito.mock(com.athuull.hera.client.DowntifyClient.class);
        SettingsService fakeSettingsService = Mockito.mock(SettingsService.class);
        when(fakeSettingsService.getSettings()).thenReturn(AppSettings.builder().build());

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode albumNode = mapper.createObjectNode();
        albumNode.put("track1", "file1.mp3");
        albumNode.put("track2", "file2.mp3");
        when(fakeClient.downloadAlbum(anyString())).thenReturn(albumNode);

        DownloadService svc = new DownloadService(
                fakeClient,
                Mockito.mock(com.athuull.hera.config.DowntifyConfig.class),
                fakeSettingsService,
                Mockito.mock(DeduplicationService.class),
                Mockito.mock(FormatCleanupService.class),
                Mockito.mock(com.athuull.hera.ws.ProgressWebSocketHandler.class)
        );

        String albumUrl = "https://music.youtube.com/browse/MPREb_xyz123";
        DownloadResult result = svc.downloadUrlOrAlbum(albumUrl);

        assertEquals("done", result.getStatus());
        verify(fakeClient).downloadAlbum(albumUrl);
    }

    @Test
    @DisplayName("downloadUrlOrAlbum routes resolved multi-track URL to downloadBatch")
    void testDownloadUrlOrAlbumWithResolvedBatch() {
        com.athuull.hera.client.DowntifyClient fakeClient =
                Mockito.mock(com.athuull.hera.client.DowntifyClient.class);
        SettingsService fakeSettingsService = Mockito.mock(SettingsService.class);
        when(fakeSettingsService.getSettings()).thenReturn(AppSettings.builder().build());

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode songsArray = mapper.createArrayNode();
        songsArray.addObject().put("title", "Song 1");
        songsArray.addObject().put("title", "Song 2");
        when(fakeClient.resolveUrl(anyString())).thenReturn(songsArray);

        DownloadService svc = new DownloadService(
                fakeClient,
                Mockito.mock(com.athuull.hera.config.DowntifyConfig.class),
                fakeSettingsService,
                Mockito.mock(DeduplicationService.class),
                Mockito.mock(FormatCleanupService.class),
                Mockito.mock(com.athuull.hera.ws.ProgressWebSocketHandler.class)
        );

        String spotifyAlbumUrl = "https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy";
        DownloadResult result = svc.downloadUrlOrAlbum(spotifyAlbumUrl);

        assertEquals("queued", result.getStatus());
        verify(fakeClient).downloadBatch(anyList(), eq(spotifyAlbumUrl), eq(false));
    }
}
