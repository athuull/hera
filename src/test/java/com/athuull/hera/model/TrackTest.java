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
}
