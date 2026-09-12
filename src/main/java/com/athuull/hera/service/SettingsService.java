package com.athuull.hera.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.athuull.hera.model.AppSettings;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final String SETTINGS_FILE = "settings.json";
    private final ObjectMapper mapper;

    public SettingsService() {
        this(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SettingsService(ObjectMapper mapper) {
        this.mapper = mapper != null
                ? mapper.copy().enable(SerializationFeature.INDENT_OUTPUT)
                : new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Value("${app.config-dir:./}")
    private String configDir;

    @Value("${lastfm.api-key:}")
    private String envApiKey;

    @Value("${scheduler.lastfm-username:}")
    private String envUsername;

    @Value("${scheduler.max-daily-downloads:5}")
    private int envMaxDownloads;

    @Value("${scheduler.cron:0 0 2 * * *}")
    private String envCron;

    @Value("${downtify.format:mp3}")
    private String envFormat;

    @Value("${downtify.bitrate:320}")
    private String envBitrate;

    @Value("${downtify.organize-by-artist:true}")
    private boolean envOrganize;

    @Value("${downtify.download-lyrics:false}")
    private boolean envLyrics;

    @Value("${downtify.download-cover-art:true}")
    private boolean envCoverArt;

    @Value("${downtify.cover-resolution:600}")
    private int envCoverResolution;

    private volatile AppSettings settings;
    private File settingsFile;

    @PostConstruct
    public void init() {
        File dir = new File(configDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        settingsFile = new File(dir, SETTINGS_FILE);

        if (settingsFile.exists()) {
            try {
                settings = mapper.readValue(settingsFile, AppSettings.class);
                if (settings.getCoverResolution() <= 0) {
                    settings.setCoverResolution(600);
                }
                log.info("Loaded settings from {}", settingsFile.getAbsolutePath());
            } catch (IOException e) {
                log.error("Failed to read settings file, creating from env defaults: {}", e.getMessage());
                createDefaults();
            }
        } else {
            log.info("No settings file found, creating from env defaults");
            createDefaults();
        }
    }

    private void createDefaults() {
        settings = AppSettings.builder()
                .lastfmApiKey(envApiKey)
                .lastfmUsername(envUsername)
                .maxDailyDownloads(envMaxDownloads)
                .cronSchedule(envCron)
                .scheduledStrategy(com.athuull.hera.model.RecommendationStrategy.HYBRID)
                .format(envFormat)
                .bitrate(envBitrate)
                .organizeByArtist(envOrganize)
                .downloadLyrics(envLyrics)
                .downloadCoverArt(envCoverArt)
                .coverResolution(envCoverResolution > 0 ? envCoverResolution : 600)
                .build();
        saveSettings();
    }

    public AppSettings getSettings() {
        return settings;
    }

    public synchronized AppSettings updateSettings(AppSettings newSettings) {
        this.settings = newSettings;
        saveSettings();
        return this.settings;
    }

    private synchronized void saveSettings() {
        if (settingsFile == null) return;
        File tempFile = new File(settingsFile.getParentFile(), SETTINGS_FILE + ".tmp");
        try {
            mapper.writeValue(tempFile, settings);
            if (!tempFile.renameTo(settingsFile)) {
                java.nio.file.Files.move(
                        tempFile.toPath(),
                        settingsFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                );
            }
        } catch (Exception e) {
            log.error("Failed to save settings file atomically: {}", e.getMessage());
            try {
                mapper.writeValue(settingsFile, settings);
            } catch (IOException ex) {
                log.error("Failed to fallback-write settings file: {}", ex.getMessage());
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
}