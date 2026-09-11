package com.athuull.hera.service;

import com.athuull.hera.model.Track;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TrackMatcher {

    /**
     * Determines whether a YouTube Music candidate plausibly matches the requested track.
     */
    public boolean isPlausibleMatch(Track requested, String matchedArtist, String matchedTitle) {
        if (matchedTitle == null || matchedTitle.isBlank()) return false;

        // Titles: only strip feat credits and YouTube noise — (Skit), (Remix), (Live) etc. must survive
        // so they can distinguish fundamentally different tracks.
        String reqTitle = cleanTitle(requested.getTitle());
        String gotTitle = cleanTitle(matchedTitle);

        // Artists: strip everything aggressively — feat lists, collaboration credits, etc.
        String reqArtist = cleanForComparison(requested.getArtist());
        String gotArtist = matchedArtist == null ? "" : cleanForComparison(matchedArtist);

        // Title: require equality after cleaning.
        boolean titleMatches = gotTitle.equals(reqTitle);

        // Artist: matched artist must contain the full requested name (safe direction only).
        boolean artistOverlaps = false;
        if (gotArtist.isBlank() || gotArtist.contains(reqArtist)) {
            artistOverlaps = true;
        } else if (requested.getArtist() != null && (requested.getArtist().contains(";") || requested.getArtist().contains("/"))) {
            // Semicolon/slash-separated artist credits (e.g. "Tory Lanez; Tee" or "BoyWithUke; blackbear")
            String[] parts = requested.getArtist().split("[;/]");
            for (String part : parts) {
                String cleanPart = cleanForComparison(part);
                if (!cleanPart.isBlank() && gotArtist.contains(cleanPart)) {
                    artistOverlaps = true;
                    break;
                }
            }
        }

        return titleMatches && artistOverlaps;
    }

    /**
     * Cleans a track TITLE for plausibility comparison.
     * Only strips feat/ft credits and known YouTube Music noise annotations (Official Video, Audio, etc.).
     * Preserves meaningful parenthetical identifiers like (Skit), (Remix), (Live), (Acoustic), etc.
     */
    public String cleanTitle(String input) {
        if (input == null) return "";
        String s = input.toLowerCase();
        // Strip feat/ft credits inside parens or brackets only
        s = s.replaceAll("\\(\\s*f(?:eat|t)\\.?[^)]*\\)", "");
        s = s.replaceAll("\\[\\s*f(?:eat|t)\\.?[^\\]]*\\]", "");
        // Strip YouTube Music noise annotations that don't change the track's identity
        s = s.replaceAll("\\(\\s*(?:official\\s+(?:video|audio|music\\s+video)|music\\s+video|audio|lyric(?:s|\\s+video)?|visuali[zs]er|hd|hq)\\s*\\)", "");
        // Strip bare feat./ft. that wasn't already in parens
        s = s.replaceAll("\\s+f(?:eat|t)\\..*", "");
        return s.trim();
    }

    /**
     * Cleans an ARTIST string for plausibility comparison.
     * Aggressively strips all parenthetical content (feat lists, collaboration credits, etc.)
     * because artist display names have no meaningful parenthetical distinctions.
     */
    public String cleanForComparison(String input) {
        if (input == null) return "";
        String s = input.toLowerCase();
        // Strip all parenthesised and bracketed annotations
        s = s.replaceAll("\\([^)]*\\)", "");
        s = s.replaceAll("\\[[^\\]]*\\]", "");
        // Strip everything after a bare feat. / ft. that wasn't already in parens
        s = s.replaceAll("\\s+feat\\..*", "");
        s = s.replaceAll("\\s+ft\\..*", "");
        return s.trim();
    }

    /**
     * Extracts a displayable artist name from a single element of ytmusicapi's 'artists' array.
     * The array may contain plain strings ("Tory Lanez") or objects ({"name": "Tory Lanez", "id": "UC..."}).
     */
    public String extractArtistName(JsonNode artistNode) {
        if (artistNode == null) return "";
        if (artistNode.isObject()) return artistNode.path("name").asText("");
        return artistNode.asText("");
    }

    /**
     * Extracts title from candidate node, checking 'name' and 'title'.
     */
    public String extractCandidateTitle(JsonNode candidate) {
        if (candidate == null) return "";
        if (candidate.hasNonNull("name")) {
            return candidate.get("name").asText("");
        }
        return candidate.path("title").asText("");
    }

    /**
     * Extracts full artist representation from candidate node.
     * Combines all artists if 'artists' array is present, else falls back to 'artist'.
     */
    public String extractCandidateArtist(JsonNode candidate) {
        if (candidate == null) return "";
        JsonNode artistsNode = candidate.path("artists");
        if (artistsNode.isArray() && !artistsNode.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (JsonNode a : artistsNode) {
                String name = extractArtistName(a);
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
            if (!names.isEmpty()) {
                return String.join(" & ", names);
            }
        }
        return candidate.path("artist").asText("");
    }
}
