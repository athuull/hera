package com.lo.musicdownloader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lo.musicdownloader.model.AppSettings;
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
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Value("${app.config-dir:./}")
    private String configDir;

    @Value("${lastfm.api-key:}")
    private String envApiKey;

    @Value("${scheduler.lastfm-username:}")
    private String envUsername;

    @Value("${scheduler.max-daily-downloads:50}")
    private int envMaxDownloads;

    @Value("${downtify.format:mp3}")
    private String envFormat;

    @Value("${downtify.bitrate:320}")
    private String envBitrate;

    @Value("${downtify.organize-by-artist:true}")
    private boolean envOrganize;

    @Value("${downtify.download-lyrics:true}")
    private boolean envLyrics;

    private AppSettings settings;
    private File settingsFile;

    @PostConstruct
    public void init() {
        File dir = new File(configDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        settingsFile = new File(dir, "settings.json");

        if (settingsFile.exists()) {
            try {
                settings = mapper.readValue(settingsFile, AppSettings.class);
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
        settings = new AppSettings(
                envApiKey, envUsername, envMaxDownloads,
                envFormat, envBitrate, envOrganize, envLyrics
        );
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

    private void saveSettings() {
        try {
            mapper.writeValue(settingsFile, settings);
        } catch (IOException e) {
            log.error("Failed to save settings file: {}", e.getMessage());
        }
    }
}