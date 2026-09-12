package com.athuull.hera.service;

import com.athuull.hera.client.DowntifyClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class DeduplicationServiceTest {

    private DowntifyClient downtifyClient;
    private DeduplicationService dedupService;

    @BeforeEach
    void setUp() {
        downtifyClient = Mockito.mock(DowntifyClient.class);
        dedupService = new DeduplicationService(downtifyClient);
    }

    @Test
    @DisplayName("normalizeFilename strips Unix path, extensions, and formats key")
    void testNormalizeFilenameUnix() {
        String filename = "/music/downloads/Radiohead/Radiohead - Karma Police (Remastered).mp3";
        String normalized = dedupService.normalizeFilename(filename);
        assertEquals("radiohead - karma police", normalized);
    }

    @Test
    @DisplayName("normalizeFilename strips Windows path separators correctly")
    void testNormalizeFilenameWindows() {
        String filename = "C:\\Media\\Music\\Daft Punk\\Daft Punk - One More Time.flac";
        String normalized = dedupService.normalizeFilename(filename);
        assertEquals("daft punk - one more time", normalized);
    }

    @Test
    @DisplayName("alreadyDownloaded performs exact key match without false substring hits")
    void testAlreadyDownloadedExactMatching() {
        when(downtifyClient.listFiles()).thenReturn(List.of(
                "The Beatles - Something.mp3",
                "Radiohead - Karma Police.flac"
        ));
        dedupService.refreshIndex();

        // Exact match
        assertTrue(dedupService.alreadyDownloaded("Radiohead", "Karma Police"));
        assertTrue(dedupService.alreadyDownloaded("The Beatles", "Something"));

        // Substring that should NOT falsely match "Something"
        assertFalse(dedupService.alreadyDownloaded("The Beatles", "Some"));
        // Track never downloaded
        assertFalse(dedupService.alreadyDownloaded("Radiohead", "Creep"));
    }

    @Test
    @DisplayName("alreadyDownloaded recognizes collaborative track across semicolon, ampersand, comma, and feat variations")
    void testAlreadyDownloadedCollaborativeArtistFormats() {
        when(downtifyClient.listFiles()).thenReturn(List.of(
                "downloads/Tory Lanez/Tory Lanez, Tee - Pink Dolphin Sunset.mp3",
                "A$AP Mob/A$AP Mob, A$AP Rocky, Playboi Carti & Big Sean - Frat Rules (feat. A$AP Rocky, Playboi Carti & Big Sean).mp3"
        ));
        dedupService.refreshIndex();

        // Requested as semicolon-separated (Last.fm format)
        assertTrue(dedupService.alreadyDownloaded("Tory Lanez; Tee", "Pink Dolphin Sunset (feat. Tee)"));
        // Requested as ampersand-separated (YouTube Music format)
        assertTrue(dedupService.alreadyDownloaded("Tory Lanez & Tee", "Pink Dolphin Sunset"));
        // Requested as solo primary artist
        assertTrue(dedupService.alreadyDownloaded("Tory Lanez", "Pink Dolphin Sunset"));
        // Requested as featured artist
        assertTrue(dedupService.alreadyDownloaded("Tee", "Pink Dolphin Sunset"));
        // Requested as comma-separated
        assertTrue(dedupService.alreadyDownloaded("Tory Lanez, Tee", "Pink Dolphin Sunset"));

        // A$AP Mob collaborative track
        assertTrue(dedupService.alreadyDownloaded("A$AP Mob", "Frat Rules (feat. A$AP Rocky, Playboi Carti & Big Sean)"));
        assertTrue(dedupService.alreadyDownloaded("A$AP Mob", "Frat Rules"));

        // Unrelated track should not match
        assertFalse(dedupService.alreadyDownloaded("Tory Lanez", "Say It"));
    }

    @Test
    @DisplayName("alreadyDownloaded recognizes track-numbered album files and prevents duplicate downloads")
    void testTrackNumberedFilesDeduplication() {
        when(downtifyClient.listFiles()).thenReturn(List.of(
                "Burial/Untrue/04 - Ghost Hardware.flac",
                "Burial/Untrue/07 - In McDonalds.flac",
                "Kendrick Lamar/DAMN./01 - Blood.flac",
                "Kendrick Lamar/good kid, m.A.A.d city/04 The art of peer pressure.m4a",
                "C418/1-09 For the Sake of Making Games.flac",
                "Daft Punk/Random Access Memories/05 Instant Crush (feat. Julian Casablancas).flac",
                "Four Tet/Rounds/01. Hands.flac",
                "Aphex Twin/Selected Ambient Works 85-92/1. Xtal.flac",
                "The Weeknd/Trilogy/01. The Weeknd - High For This.flac"
        ));
        dedupService.refreshIndex();

        // Burial solo tracks requested as solo or under collaborative artist name
        assertTrue(dedupService.alreadyDownloaded("Burial", "Ghost Hardware"));
        assertTrue(dedupService.alreadyDownloaded("Burial & Four Tet", "Ghost Hardware"));
        assertTrue(dedupService.alreadyDownloaded("Burial", "In McDonalds"));
        assertTrue(dedupService.alreadyDownloaded("Burial & Four Tet", "In McDonalds"));

        // Kendrick Lamar tracks
        assertTrue(dedupService.alreadyDownloaded("Kendrick Lamar", "Blood"));
        assertTrue(dedupService.alreadyDownloaded("Kendrick Lamar", "The Art of Peer Pressure"));

        // C418 track
        assertTrue(dedupService.alreadyDownloaded("C418", "For the Sake of Making Games"));

        // Daft Punk track
        assertTrue(dedupService.alreadyDownloaded("Daft Punk", "Instant Crush"));
        assertTrue(dedupService.alreadyDownloaded("Daft Punk, Julian Casablancas", "Instant Crush (feat. Julian Casablancas)"));

        // Four Tet & Aphex Twin
        assertTrue(dedupService.alreadyDownloaded("Four Tet", "Hands"));
        assertTrue(dedupService.alreadyDownloaded("Aphex Twin", "Xtal"));

        // The Weeknd
        assertTrue(dedupService.alreadyDownloaded("The Weeknd", "High For This"));

        // New track not in library
        assertFalse(dedupService.alreadyDownloaded("Burial", "Street Halo"));
    }

    @Test
    @DisplayName("alreadyDownloaded correctly distinguishes different non-Latin / multilingual tracks from the same artist")
    void testNonLatinUnicodeTracksDeduplication() {
        when(downtifyClient.listFiles()).thenReturn(List.of(
                "Macroblank/Macroblank - Macroblank - лучшие дни.mp3"
        ));
        dedupService.refreshIndex();

        // Already in library: лучшие дни
        assertTrue(dedupService.alreadyDownloaded("Macroblank", "лучшие дни"));

        // Different song in Japanese/Chinese not yet in library: 能界蘭極境
        assertFalse(dedupService.alreadyDownloaded("Macroblank", "能界蘭極境"));
    }
}
