package com.athuull.hera.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "downtify")
@Data
public class DowntifyConfig {
    private String baseUrl;
    private String downloadDir;
    private String format;
    private String bitrate;
    private boolean organizeByArtist;
    private boolean downloadLyrics;
    private int maxConcurrentDownloads;
    private long pollIntervalMs;
    private long pollTimeoutMs;
}
