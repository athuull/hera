package com.athuull.hera.service;

import com.athuull.hera.client.DowntifyClient;
import com.athuull.hera.model.Track;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);
    private final DowntifyClient downtifyClient;
    private volatile Set<String> knownFiles = Collections.emptySet();

    @Autowired
    public DeduplicationService(DowntifyClient downtifyClient) {
        this.downtifyClient = downtifyClient;
    }

    @PostConstruct
    public void init() {
        refreshIndex();
    }

    public void refreshIndex() {
        try {
            List<String> files = downtifyClient.listFiles();
            Set<String> set = files.stream()
                    .map(this::normalizeFilename)
                    .collect(Collectors.toSet());
            this.knownFiles = Collections.unmodifiableSet(set);
            log.info("Deduplication index refreshed: {} files known", knownFiles.size());
        } catch (Exception e) {
            log.warn("Could not refresh deduplication index: {}", e.getMessage());
        }
    }

    public boolean alreadyDownloaded(String artist, String title) {
        Track t = new Track(artist, title, null, null);
        String key = t.dedupeKey();
        return knownFiles.contains(key);
    }

    public String normalizeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "";

        // 1. Strip directory path across Unix (/) and Windows (\) separators
        String normalized = filename.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1);

        // 2. Strip file extension
        for (String ext : new String[]{".mp3", ".flac", ".m4a", ".opus", ".ogg", ".wav", ".webm"}) {
            if (normalized.toLowerCase().endsWith(ext)) {
                normalized = normalized.substring(0, normalized.length() - ext.length());
                break;
            }
        }

        // 3. Always route through Track.dedupeKey()
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