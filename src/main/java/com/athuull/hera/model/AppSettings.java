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
    private String timezone;
    private RecommendationStrategy scheduledStrategy;
    private String format;
    private String bitrate;
    private boolean organizeByArtist;
    private boolean downloadLyrics;
    @Builder.Default
    private boolean downloadCoverArt = true;
    @Builder.Default
    private int coverResolution = 600;

    public int getCoverResolution() {
        return coverResolution > 0 ? coverResolution : 600;
    }
}