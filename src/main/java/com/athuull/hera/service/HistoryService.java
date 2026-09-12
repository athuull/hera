package com.athuull.hera.service;

import com.athuull.hera.model.HistoryEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);
    private static final String HISTORY_FILE = "history.json";
    private static final int MAX_ENTRIES = 200;

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final LinkedList<HistoryEntry> entries = new LinkedList<>();

    @Value("${app.config-dir:./}")
    private String configDir;

    private File historyFile;

    @PostConstruct
    public void init() {
        File dir = new File(configDir);
        if (!dir.exists()) dir.mkdirs();
        historyFile = new File(dir, HISTORY_FILE);

        if (historyFile.exists()) {
            try {
                List<HistoryEntry> loaded = mapper.readValue(historyFile, new TypeReference<List<HistoryEntry>>() {});
                entries.addAll(loaded);
                log.info("Loaded {} history entries from {}", entries.size(), historyFile.getAbsolutePath());
            } catch (Exception e) {
                log.warn("Could not load history file: {}", e.getMessage());
            }
        } else {
            log.info("No history file found at {}, starting fresh", historyFile.getAbsolutePath());
        }
    }

    public synchronized void record(HistoryEntry entry) {
        if (entry == null) return;
        entries.addFirst(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
        persist();
    }

    public synchronized List<HistoryEntry> getRecent(int limit) {
        int count = Math.min(limit, entries.size());
        return Collections.unmodifiableList(new ArrayList<>(entries.subList(0, count)));
    }

    public synchronized List<HistoryEntry> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized void clear() {
        entries.clear();
        persist();
        log.info("History cleared");
    }

    private void persist() {
        if (historyFile == null) return;
        File tempFile = new File(historyFile.getParentFile(), HISTORY_FILE + ".tmp");
        try {
            mapper.writeValue(tempFile, entries);
            if (!tempFile.renameTo(historyFile)) {
                // Fallback: write directly
                mapper.writeValue(historyFile, entries);
                tempFile.delete();
            }
        } catch (Exception e) {
            log.warn("Failed to persist history: {}", e.getMessage());
            tempFile.delete();
        }
    }
}
