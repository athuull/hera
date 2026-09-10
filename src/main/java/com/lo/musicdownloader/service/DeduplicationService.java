package com.lo.musicdownloader.service;

import com.lo.musicdownloader.client.DowntifyClient;
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
        List<String> files = downtifyClient.listFiles();
        knownFiles = files.stream()
                .map(this::normalizeFilename)
                .collect(Collectors.toSet());
        log.info("Deduplication index refreshed: {} files known", knownFiles.size());
    }

    public boolean alreadyDownloaded(String artist, String title) {
        String key = normalizeFilename(artist + " - " + title);
        return knownFiles.stream().anyMatch(f -> f.contains(key));
    }

    private String normalizeFilename(String filename) {
        String normalized = filename.toLowerCase().trim();
        for (String ext : new String[]{".mp3", ".flac", ".m4a", ".opus", ".ogg", ".wav"}) {
            if (normalized.endsWith(ext)) {
                normalized = normalized.substring(0, normalized.length() - ext.length());
            }
        }
        return normalized;
    }

    public int getKnownFileCount() {
        return knownFiles.size();
    }
}
