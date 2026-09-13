package com.athuull.hera.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of a single download attempt (successful, failed, or skipped).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoryEntry {
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    private String artist;
    private String title;
    private String filename;
    private String source;   // NIGHTLY_AUTO, MANUAL_SEARCH, URL_IMPORT
    private String reason;   // e.g. "similar to Radiohead", "genre: shoegaze", "pasted link"
    private String status;   // SUCCESS, FAILED, SKIPPED

    @Builder.Default
    private String timestamp = Instant.now().toString();
}
