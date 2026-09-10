package com.lo.musicdownloader.model;

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
        return (artist + " - " + title).toLowerCase().trim();
    }
}
