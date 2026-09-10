package com.lo.musicdownloader.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppSettings {
    private String lastfmApiKey;
    private String lastfmUsername;
    private int maxDailyDownloads;
    private String format;
    private String bitrate;
    private boolean organizeByArtist;
    private boolean downloadLyrics;
}