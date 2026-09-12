package com.athuull.hera.service;

import com.athuull.hera.model.HistoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryServiceTest {

    private HistoryService historyService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        historyService = new HistoryService();
        ReflectionTestUtils.setField(historyService, "configDir", tempDir.toString());
        historyService.init();
    }

    @Test
    @DisplayName("Recording an entry adds it to recent list and creates history.json")
    void testRecordAndPersist() {
        HistoryEntry entry = HistoryEntry.builder()
                .artist("Radiohead")
                .title("Karma Police")
                .filename("Radiohead - Karma Police.mp3")
                .source("NIGHTLY_AUTO")
                .reason("nightly hybrid discovery")
                .status("SUCCESS")
                .build();

        historyService.record(entry);

        List<HistoryEntry> recent = historyService.getRecent(10);
        assertEquals(1, recent.size());
        assertEquals("Radiohead", recent.get(0).getArtist());
        assertEquals("Karma Police", recent.get(0).getTitle());
        assertEquals("SUCCESS", recent.get(0).getStatus());

        File file = new File(tempDir.toFile(), "history.json");
        assertTrue(file.exists());
    }

    @Test
    @DisplayName("Capping history entries at MAX_ENTRIES (200)")
    void testMaxEntriesCapping() {
        for (int i = 0; i < 250; i++) {
            historyService.record(HistoryEntry.builder()
                    .artist("Artist " + i)
                    .title("Track " + i)
                    .source("MANUAL_SEARCH")
                    .status("SUCCESS")
                    .build());
        }

        List<HistoryEntry> all = historyService.getAll();
        assertEquals(200, all.size());
        // Most recent should be first
        assertEquals("Artist 249", all.get(0).getArtist());
    }

    @Test
    @DisplayName("Reloads history on init")
    void testReloadOnInit() {
        historyService.record(HistoryEntry.builder()
                .artist("Aphex Twin")
                .title("Windowlicker")
                .source("URL_IMPORT")
                .status("SUCCESS")
                .build());

        HistoryService newService = new HistoryService();
        ReflectionTestUtils.setField(newService, "configDir", tempDir.toString());
        newService.init();

        List<HistoryEntry> recent = newService.getRecent(10);
        assertEquals(1, recent.size());
        assertEquals("Aphex Twin", recent.get(0).getArtist());
    }

    @Test
    @DisplayName("Clearing history removes all entries and updates persistent file")
    void testClearHistory() {
        historyService.record(HistoryEntry.builder()
                .artist("Portishead")
                .title("Glory Box")
                .source("NIGHTLY_AUTO")
                .status("SUCCESS")
                .build());

        assertEquals(1, historyService.getAll().size());

        historyService.clear();

        assertTrue(historyService.getAll().isEmpty());
    }
}
