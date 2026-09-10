package com.athuull.hera.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppSettings {
    private String lastfmApiKey;
    private String lastfmUsername;
    private int maxDailyDownloads;
    private String cronSchedule;
    private RecommendationStrategy scheduledStrategy;
    private String format;
    private String bitrate;
    private boolean organizeByArtist;
    private boolean downloadLyrics;
}