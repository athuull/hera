package com.athuull.hera.service;

import com.athuull.hera.model.Track;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrackMatcherTest {

    private TrackMatcher matcher;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        matcher = new TrackMatcher();
    }

    @Test
    @DisplayName("cleanTitle strips YouTube noise while preserving Remix/Skit")
    void testCleanTitlePreservesMeaningfulParens() {
        assertEquals("say it (skit)", matcher.cleanTitle("Say It (Skit) (Official Video)"));
        assertEquals("creep (acoustic)", matcher.cleanTitle("Creep (Acoustic) (Lyrics)"));
        assertEquals("show me how", matcher.cleanTitle("Show Me How (Official Music Video)"));
        assertEquals("pink dolphin sunset", matcher.cleanTitle("Pink Dolphin Sunset (feat. Tee)"));
    }

    @Test
    @DisplayName("cleanForComparison strips all parenthetical notes and features")
    void testCleanForComparisonAggressive() {
        assertEquals("raf", matcher.cleanForComparison("RAF (feat. A$AP Rocky, Playboi Carti, Quavo & Lil Uzi Vert)"));
        assertEquals("humble.", matcher.cleanForComparison("HUMBLE. feat. SomeArtist"));
    }

    @Test
    @DisplayName("isPlausibleMatch accepts collaborative credits and rejects different artists")
    void testIsPlausibleMatch() {
        Track reqCollab = new Track("Tory Lanez; Tee", "Pink Dolphin Sunset (feat. Tee)", null, null);
        assertTrue(matcher.isPlausibleMatch(reqCollab, "Tory Lanez & Tee", "Pink Dolphin Sunset"));
        assertTrue(matcher.isPlausibleMatch(reqCollab, "Tory Lanez", "Pink Dolphin Sunset"));
        assertFalse(matcher.isPlausibleMatch(reqCollab, "Lanez", "Pink Dolphin Sunset"));
    }

    @Test
    @DisplayName("extractCandidateArtist handles arrays of objects and plain text")
    void testExtractCandidateArtist() throws Exception {
        JsonNode node = mapper.readTree("{\"artists\": [{\"name\": \"Tory Lanez\"}, {\"name\": \"Tee\"}], \"title\": \"Pink Dolphin Sunset\"}");
        assertEquals("Tory Lanez & Tee", matcher.extractCandidateArtist(node));
        assertEquals("Pink Dolphin Sunset", matcher.extractCandidateTitle(node));
    }
}
