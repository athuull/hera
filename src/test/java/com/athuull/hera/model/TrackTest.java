package com.athuull.hera.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrackTest {

    @Test
    @DisplayName("dedupeKey removes parenthesized and bracketed info")
    void testDedupeKeyRemovesParenthesesAndBrackets() {
        Track t1 = new Track("Daft Punk", "Get Lucky (Radio Edit) [Remastered]", null, null);
        assertEquals("daft punk - get lucky", t1.dedupeKey());
    }

    @Test
    @DisplayName("dedupeKey removes feat. and ft. variations")
    void testDedupeKeyRemovesFeatures() {
        Track t1 = new Track("Gorillaz feat. 2D", "Clint Eastwood ft. Del the Funky Homosapien", null, null);
        assertEquals("gorillaz - clint eastwood", t1.dedupeKey());
    }

    @Test
    @DisplayName("dedupeKey handles punctuation and collapses multiple spaces")
    void testDedupeKeyCleansPunctuationAndSpacing() {
        Track t1 = new Track("Tyler, The Creator", "See You Again (feat. Kali Uchis)", null, null);
        assertEquals("tyler - see you again", t1.dedupeKey());
    }

    @Test
    @DisplayName("toSearchQuery formats query with artist and title")
    void testToSearchQuery() {
        Track t = new Track("Radiohead", "Creep", null, null);
        assertEquals("Radiohead Creep", t.toSearchQuery());
    }

    @Test
    @DisplayName("toSearchQuery replaces semicolons with spaces for multi-artist credits")
    void testToSearchQueryWithSemicolons() {
        Track t = new Track("Tory Lanez; Tee", "Pink Dolphin Sunset (feat. Tee)", null, null);
        assertEquals("Tory Lanez Tee Pink Dolphin Sunset (feat. Tee)", t.toSearchQuery());
    }

    @Test
    @DisplayName("dedupeKey normalizes semicolon, ampersand, comma, and slash multi-artist credits identically")
    void testDedupeKeyMultiArtistConsistency() {
        Track tSemicolon = new Track("Tory Lanez; Tee", "Pink Dolphin Sunset (feat. Tee)", null, null);
        Track tAmpersand = new Track("Tory Lanez & Tee", "Pink Dolphin Sunset", null, null);
        Track tComma = new Track("Tory Lanez, Tee", "Pink Dolphin Sunset", null, null);
        Track tSlash = new Track("Tory Lanez / Tee", "Pink Dolphin Sunset (feat. Tee)", null, null);
        Track tSolo = new Track("Tory Lanez", "Pink Dolphin Sunset", null, null);

        assertEquals("tory lanez - pink dolphin sunset", tSemicolon.dedupeKey());
        assertEquals("tory lanez - pink dolphin sunset", tAmpersand.dedupeKey());
        assertEquals("tory lanez - pink dolphin sunset", tComma.dedupeKey());
        assertEquals("tory lanez - pink dolphin sunset", tSlash.dedupeKey());
        assertEquals("tory lanez - pink dolphin sunset", tSolo.dedupeKey());
    }

    @Test
    @DisplayName("cleanTitle does not truncate song titles that have commas")
    void testCleanTitlePreservesTitlesWithCommas() {
        Track t = new Track("The Beatles", "Hello, Goodbye", null, null);
        assertEquals("the beatles - hello goodbye", t.dedupeKey());
    }
}
