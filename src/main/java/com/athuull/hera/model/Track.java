package com.athuull.hera.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Track {
    private String artist;
    private String title;
    private String spotifyUrl;
    private String duration;

    public String toSearchQuery() {
        String cleanArtist = artist != null ? artist.replace(';', ' ').replaceAll("\\s+", " ").trim() : "";
        String cleanTitle = title != null ? title.trim() : "";
        return (cleanArtist + " " + cleanTitle).trim();
    }

    public String dedupeKey() {
        String cleanArt = cleanArtist(artist);
        String cleanTit = cleanTitle(title);
        return (cleanArt + " - " + cleanTit).toLowerCase().trim();
    }

    public static String cleanArtist(String input) {
        if (input == null) return "";
        // Remove anything in parentheses or brackets
        String cleaned = input.replaceAll("\\s*\\(.*?\\)\\s*", " ");
        cleaned = cleaned.replaceAll("\\s*\\[.*?\\]\\s*", " ");
        // Split on standard multi-artist delimiters: comma, semicolon, slash, ampersand, plus
        cleaned = cleaned.split("[,;/&+]")[0];
        // Split on feat/ft/featuring/with/x/vs (case-insensitive)
        cleaned = cleaned.split("(?i)\\s+(?:feat\\.?|ft\\.?|featuring|with|x|vs\\.?)\\s+")[0];
        cleaned = cleaned.split("(?i)\\s+f(?:eat|t)\\..*")[0];
        // Remove non-alphanumeric characters except whitespace
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9\\s]", "");
        // Collapse any double spaces
        cleaned = cleaned.replaceAll("\\s+", " ");
        return cleaned.trim();
    }

    public static String cleanTitle(String input) {
        if (input == null) return "";
        // Remove anything in parentheses or brackets
        String cleaned = input.replaceAll("\\s*\\(.*?\\)\\s*", " ");
        cleaned = cleaned.replaceAll("\\s*\\[.*?\\]\\s*", " ");
        // Remove "feat.", "ft.", "featuring" and everything after it (case-insensitive)
        cleaned = cleaned.replaceAll("(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\b.*", "");
        // Remove non-alphanumeric characters except whitespace (do not split on comma!)
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9\\s]", "");
        // Collapse any double spaces
        cleaned = cleaned.replaceAll("\\s+", " ");
        return cleaned.trim();
    }
}