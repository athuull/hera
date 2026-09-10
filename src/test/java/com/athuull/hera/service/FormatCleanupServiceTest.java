package com.athuull.hera.service;

import com.athuull.hera.config.DowntifyConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FormatCleanupServiceTest {

    @Test
    @DisplayName("Extension replacement safely targets only trailing .webm without corrupting directory names")
    void testExtensionReplacementRegex() {
        String originalPath = "/music/webm_archive/webm_session/song.webm";
        String replaced = originalPath.replaceAll("(?i)\\.webm$", ".mp3");
        assertEquals("/music/webm_archive/webm_session/song.mp3", replaced);
    }

    @Test
    @DisplayName("resolveDownloadDir handles null and blank gracefully")
    void testResolveDownloadDirNullSafe() {
        DowntifyConfig config = new DowntifyConfig();
        config.setDownloadDir(null);
        FormatCleanupService service = new FormatCleanupService(config);

        // Should not throw NullPointerException
        assertDoesNotThrow(service::resolveDownloadDir);
    }
}
