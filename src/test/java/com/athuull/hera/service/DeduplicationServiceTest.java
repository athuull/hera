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
}
