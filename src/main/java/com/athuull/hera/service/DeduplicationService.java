package com.athuull.hera.service;

import com.athuull.hera.client.DowntifyClient;
import com.athuull.hera.model.Track;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
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
            Set<String> set = new HashSet<>();
            for (String file : files) {
                set.addAll(normalizeFilenameToKeys(file));
            }
            this.knownFiles = Collections.unmodifiableSet(set);
            log.info("Deduplication index refreshed: {} files known ({} dedupe keys indexed)",
                    files.size(), knownFiles.size());
        } catch (Exception e) {
            log.warn("Could not refresh deduplication index: {}", e.getMessage());
        }
    }

    public boolean alreadyDownloaded(String artist, String title) {
        if (artist == null && title == null) return false;
        // 1. Primary dedupe key
        Track t = new Track(artist, title, null, null);
        String key = t.dedupeKey();
        if (knownFiles.contains(key)) return true;

        // 2. Multi-artist collaborative variations
        String cleanTitle = Track.cleanTitle(title);
        if (!cleanTitle.isBlank() && artist != null) {
            for (String art : extractAllArtists(artist)) {
                String subKey = (art + " - " + cleanTitle).toLowerCase().trim();
                if (knownFiles.contains(subKey)) return true;
            }
        }
        return false;
    }

    public String stripPathAndExt(String filename) {
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
        return normalized;
    }

    public String normalizeFilename(String filename) {
        String base = stripPathAndExt(filename);
        if (base.isBlank()) return "";

        String[] parts = base.split(" - ", 2);
        Track t = (parts.length == 2)
                ? new Track(parts[0], parts[1], null, null)
                : new Track("", base, null, null);
        return t.dedupeKey();
    }

    public Set<String> normalizeFilenameToKeys(String filename) {
        String base = stripPathAndExt(filename);
        if (base.isBlank()) return Collections.emptySet();

        String[] parts = base.split(" - ", 2);
        if (parts.length < 2) {
            String singleKey = new Track("", base, null, null).dedupeKey();
            return singleKey.isBlank() ? Collections.emptySet() : Collections.singleton(singleKey);
        }

        Set<String> keys = new HashSet<>();
        // 1. Primary dedupeKey
        keys.add(new Track(parts[0], parts[1], null, null).dedupeKey());

        // 2. Keys for each individual collaborative artist
        String cleanTitle = Track.cleanTitle(parts[1]);
        if (!cleanTitle.isBlank()) {
            for (String art : extractAllArtists(parts[0])) {
                keys.add((art + " - " + cleanTitle).toLowerCase().trim());
            }
        }
        return keys;
    }

    public List<String> extractAllArtists(String artistStr) {
        if (artistStr == null || artistStr.isBlank()) return Collections.emptyList();
        String cleaned = artistStr.replaceAll("\\s*\\(.*?\\)\\s*", " ");
        cleaned = cleaned.replaceAll("\\s*\\[.*?\\]\\s*", " ");
        String[] raw = cleaned.split("(?i)[,;/&+]|\\s+(?:feat\\.?|ft\\.?|featuring|with|x|vs\\.?)\\s+");
        List<String> list = new ArrayList<>();
        for (String r : raw) {
            String art = Track.cleanArtist(r);
            if (!art.isBlank() && !list.contains(art)) {
                list.add(art);
            }
        }
        return list;
    }

    public int getKnownFileCount() {
        return knownFiles.size();
    }
}