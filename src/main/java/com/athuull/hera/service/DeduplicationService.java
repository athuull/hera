package com.athuull.hera.service;

import com.athuull.hera.client.DowntifyClient;
import com.athuull.hera.model.Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);
    private final DowntifyClient downtifyClient;
    private Set<String> knownFiles;

    @Autowired
    public DeduplicationService(DowntifyClient downtifyClient) {
        this.downtifyClient = downtifyClient;
        this.knownFiles = new HashSet<>();
    }

    public void refreshIndex() {
        try {
            List<String> files = downtifyClient.listFiles();
            knownFiles = files.stream()
                    .map(this::normalizeFilename)
                    .collect(Collectors.toSet());
            log.info("Deduplication index refreshed: {} files known", knownFiles.size());
        } catch (Exception e) {
            log.warn("Could not refresh deduplication index: {}", e.getMessage());
        }
    }

    public boolean alreadyDownloaded(String artist, String title) {
        Track t = new Track(artist, title, null, null);
        String key = t.dedupeKey();
        // Exact set membership, not substring contains() — contains() matched
        // unrelated filenames whose key happened to be a substring of another
        // track's key, silently filtering out recommendations never actually downloaded.
        return knownFiles.contains(key);
    }

    private String normalizeFilename(String filename) {
        // 1. Strip directory path if organize-by-artist is on
        String normalized = filename.substring(filename.lastIndexOf('/') + 1);

        // 2. Strip file extension
        for (String ext : new String[]{".mp3", ".flac", ".m4a", ".opus", ".ogg", ".wav"}) {
            if (normalized.toLowerCase().endsWith(ext)) {
                normalized = normalized.substring(0, normalized.length() - ext.length());
                break;
            }
        }

        // 3. Always route through Track.dedupeKey(), even when the filename
        // doesn't split cleanly into "Artist - Title", so the key format always
        // matches what alreadyDownloaded() looks up.
        String[] parts = normalized.split(" - ", 2);
        Track t = (parts.length == 2)
                ? new Track(parts[0], parts[1], null, null)
                : new Track("", normalized, null, null);
        return t.dedupeKey();
    }

    public int getKnownFileCount() {
        return knownFiles.size();
    }
}