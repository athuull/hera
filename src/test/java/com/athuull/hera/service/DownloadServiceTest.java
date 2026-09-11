package com.athuull.hera.service;

import com.athuull.hera.model.Track;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
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
    @DisplayName("isPlausibleMatch REJECTS 'Lanez' when requesting 'Tory Lanez' — partial name is not enough")
    void testPlausibleMatchRejectsPartialArtistName() {
        // "tory lanez".contains("lanez") = true, but that must NOT be sufficient.
        // The matched artist ("Lanez") must contain the full requested name ("tory lanez"), which it doesn't.
        Track requested = new Track("Tory Lanez", "Say It", null, null);
        assertFalse(downloadService.isPlausibleMatch(requested, "Lanez", "Say It"),
                "Should reject 'Lanez' as a match for 'Tory Lanez' — different artist");
    }

    @Test
    @DisplayName("isPlausibleMatch accepts when matched artist contains full requested name (collaborative credit)")
    void testPlausibleMatchGotArtistContainsReqArtist() {
        // e.g. YouTube Music returns "Tory Lanez & Bryson Tiller" for a collab — still a valid match
        Track requested = new Track("Tory Lanez", "Say It", null, null);
        assertTrue(downloadService.isPlausibleMatch(requested, "Tory Lanez & Bryson Tiller", "Say It"));
    }


    // ─── searchForTrack ───────────────────────────────────────────────────────

    @Test
    @DisplayName("searchForTrack skips bad top results and returns the 7th (Tory Lanez scenario)")
    void testSearchForTrackSkipsBadResults() throws Exception {
        String resultsJson = "[" +
            "{\"name\":\"The Take (feat. Chris Brown)\",\"artists\":[\"Tory Lanez\"]}," +
            "{\"name\":\"The Color Violet\",\"artists\":[\"Tory Lanez\"]}," +
            "{\"name\":\"In For It (feat. RL Grime)\",\"artists\":[\"Tory Lanez\"]}," +
            "{\"name\":\"Traphouse\",\"artists\":[\"Tory Lanez\"]}," +
            "{\"name\":\"TAlk tO Me\",\"artists\":[\"Tory Lanez\"]}," +
            "{\"name\":\"Hate To Say\",\"artists\":[\"Tory Lanez\"]}," +
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
}
