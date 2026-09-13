package com.athuull.hera.service;

import com.athuull.hera.client.DowntifyClient;
import com.athuull.hera.model.Track;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);
    private final DowntifyClient downtifyClient;
    private volatile Set<String> knownFiles = Collections.emptySet();
    private volatile Set<String> knownTitles = Collections.emptySet();

    private static final Set<String> GENERIC_TITLES = Set.of(
        "intro", "outro", "interlude", "untitled", "track", "skit", "instrumental",
        "bonus track", "audio", "bonus", "part 1", "part 2", "part 3", "part 4"
    );

    @Autowired
    public DeduplicationService(DowntifyClient downtifyClient) {
        this.downtifyClient = downtifyClient;
    }

    private volatile long lastDowntifyFailureTime = 0;

    @PostConstruct
    public void init() {
        refreshIndex();
    }

    public void refreshIndex() {
        // If Downtify failed recently (< 60 seconds), don't block the request with another 15s timeout
        if (System.currentTimeMillis() - lastDowntifyFailureTime < 60_000L) {
            log.debug("Skipping deduplication refresh because Downtify was unreachable recently");
            return;
        }

        try {
            List<String> files = downtifyClient.listFiles();
            Set<String> keySet = new HashSet<>();
            Set<String> titleSet = new HashSet<>();

            for (String file : files) {
                Set<String> keys = normalizeFilenameToKeys(file);
                keySet.addAll(keys);

                String base = stripPathAndExt(file);
                String stripped = stripTrackNumber(base);
                String title = stripped;
                if (stripped.contains(" - ")) {
                    String[] parts = stripped.split(" - ");
                    title = parts[parts.length - 1];
                }
                String cleanTitle = Track.cleanTitle(title);
                if (isDistinctiveTitle(cleanTitle)) {
                    titleSet.add(cleanTitle);
                }
            }
            this.knownFiles = Collections.unmodifiableSet(keySet);
            this.knownTitles = Collections.unmodifiableSet(titleSet);
            this.lastDowntifyFailureTime = 0;
            log.info("Deduplication index refreshed: {} files known ({} dedupe keys, {} distinctive titles indexed)",
                    files.size(), knownFiles.size(), knownTitles.size());
        } catch (Exception e) {
            this.lastDowntifyFailureTime = System.currentTimeMillis();
            log.warn("Could not refresh deduplication index: {}", e.getMessage());
        }
    }

    public boolean alreadyDownloaded(String artist, String title) {
        if (artist == null && title == null) return false;

        // 1. Primary dedupe key (artist - title)
        Track t = new Track(artist, title, null, null);
        String key = t.dedupeKey();
        if (knownFiles.contains(key)) return true;

        // 2. Multi-artist collaborative variations
        String cleanTitle = Track.cleanTitle(title);
        if (!cleanTitle.isBlank()) {
            if (artist != null) {
                for (String art : extractAllArtists(artist)) {
                    String subKey = (art + " - " + cleanTitle).toLowerCase().trim();
                    if (knownFiles.contains(subKey)) return true;
                }
            }

            // 3. Match against distinctive library track titles (e.g. "ghost hardware", "give life back to music", "instant crush", "for the sake of making games")
            if (isDistinctiveTitle(cleanTitle) && knownTitles.contains(cleanTitle)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDistinctiveTitle(String cleanTitle) {
        if (cleanTitle == null || cleanTitle.length() < 3) return false;
        return !GENERIC_TITLES.contains(cleanTitle.toLowerCase().trim());
    }

    public static String stripTrackNumber(String input) {
        if (input == null) return "";
        // Matches leading track numbers like "01 - ", "01. ", "01_ ", "01 ", "1. ", "1-09 ", "1-01 ", "10. ", "10 "
        String cleaned = input.replaceFirst("^\\s*(?:\\d+[-_.]\\d+|\\d+)[\\s\\-._]+\\s*", "");
        return cleaned.isBlank() ? input.trim() : cleaned.trim();
    }

    public static String stripPathAndExt(String filename) {
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

    public static String normalizeFilename(String filename) {
        String base = stripPathAndExt(filename);
        if (base.isBlank()) return "";

        String stripped = stripTrackNumber(base);
        String[] parts = stripped.split(" - ", 2);
        Track t = (parts.length == 2)
                ? new Track(parts[0], parts[1], null, null)
                : new Track("", stripped, null, null);
        return t.dedupeKey();
    }

    public Set<String> normalizeFilenameToKeys(String filename) {
        if (filename == null || filename.isBlank()) return Collections.emptySet();

        String normalized = filename.replace('\\', '/');
        String[] pathSegments = normalized.split("/");
        String fileWithExt = pathSegments[pathSegments.length - 1];

        String base = stripPathAndExt(fileWithExt);
        if (base.isBlank()) return Collections.emptySet();

        String stripped = stripTrackNumber(base);

        Set<String> keys = new HashSet<>();

        if (stripped.contains(" - ")) {
            String[] parts = stripped.split(" - ");
            String firstPart = parts[0];
            String lastPart = parts[parts.length - 1];

            // 1. Primary dedupeKey using first part as artist, last part as title
            keys.add(new Track(firstPart, lastPart, null, null).dedupeKey());

            // 2. Standard 2-part split (e.g. Artist - Title)
            String[] twoParts = stripped.split(" - ", 2);
            keys.add(new Track(twoParts[0], twoParts[1], null, null).dedupeKey());

            // 3. Keys for each individual collaborative artist with lastPart and twoParts[1]
            String cleanTitleLast = Track.cleanTitle(lastPart);
            if (!cleanTitleLast.isBlank()) {
                for (String art : extractAllArtists(firstPart)) {
                    keys.add((art + " - " + cleanTitleLast).toLowerCase().trim());
                }
            }
            String cleanTitleTwo = Track.cleanTitle(twoParts[1]);
            if (!cleanTitleTwo.isBlank()) {
                for (String art : extractAllArtists(twoParts[0])) {
                    keys.add((art + " - " + cleanTitleTwo).toLowerCase().trim());
                }
            }
        } else {
            // No " - " in filename (e.g. "04 - Ghost Hardware.flac" -> "Ghost Hardware", or "01 Give Life Back to Music.flac")
            String cleanTitle = Track.cleanTitle(stripped);
            if (!cleanTitle.isBlank()) {
                keys.add(new Track("", stripped, null, null).dedupeKey());

                // Check directory structure for artist names (e.g. Artist/Album/Track.ext or Artist/Track.ext)
                if (pathSegments.length >= 3) {
                    // Artist/Album/Track.ext
                    String folderArtist = Track.cleanArtist(pathSegments[pathSegments.length - 3]);
                    if (!folderArtist.isBlank()) {
                        keys.add((folderArtist + " - " + cleanTitle).toLowerCase().trim());
                        for (String art : extractAllArtists(pathSegments[pathSegments.length - 3])) {
                            keys.add((art + " - " + cleanTitle).toLowerCase().trim());
                        }
                    }
                }
                if (pathSegments.length >= 2) {
                    // Artist/Track.ext or Album/Track.ext
                    String folderArtist = Track.cleanArtist(pathSegments[pathSegments.length - 2]);
                    if (!folderArtist.isBlank()) {
                        keys.add((folderArtist + " - " + cleanTitle).toLowerCase().trim());
                        for (String art : extractAllArtists(pathSegments[pathSegments.length - 2])) {
                            keys.add((art + " - " + cleanTitle).toLowerCase().trim());
                        }
                    }
                }
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
}