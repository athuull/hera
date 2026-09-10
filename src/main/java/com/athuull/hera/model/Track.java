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
        return artist + " " + title;
    }

    public String dedupeKey() {
        String cleanArtist = cleanString(artist);
        String cleanTitle = cleanString(title);
        return (cleanArtist + " - " + cleanTitle).toLowerCase().trim();
    }

    private String cleanString(String input) {
        if (input == null) return "";
        // Remove anything in parentheses or brackets
        String cleaned = input.replaceAll("\\s*\\(.*?\\)\\s*", "");
        cleaned = cleaned.replaceAll("\\s*\\[.*?\\]\\s*", "");
        // Remove "feat." and everything after it
        cleaned = cleaned.split(" feat\\.")[0];
        cleaned = cleaned.split(" ft\\.")[0];
        cleaned = cleaned.split(",")[0];
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9\\s]", "");
        // Collapse any double spaces left behind by the removals above
        cleaned = cleaned.replaceAll("\\s+", " ");
        return cleaned.trim();
    }
}