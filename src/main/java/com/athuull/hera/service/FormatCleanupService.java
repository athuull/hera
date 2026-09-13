package com.athuull.hera.service;

import com.athuull.hera.config.DowntifyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Housekeeping service that purges obsolete intermediate files (such as raw .webm downloads)
 * left behind after conversion to high-quality MP3/FLAC.
 */
@Service
public class FormatCleanupService {

    private static final Logger log = LoggerFactory.getLogger(FormatCleanupService.class);

    private final DowntifyConfig config;

    @Autowired
    public FormatCleanupService(DowntifyConfig config) {
        this.config = config;
    }

    public List<String> cleanupWebmFiles() {
        if (!isFfmpegAvailable()) {
            log.warn("ffmpeg not found — skipping format cleanup. Install with: brew install ffmpeg");
            return Collections.emptyList();
        }

        Path downloadDir = resolveDownloadDir();
        if (downloadDir == null) {
            log.warn("Download directory not found — skipping format cleanup");
            return Collections.emptyList();
        }

        List<String> converted = new ArrayList<>();

        try (Stream<Path> files = Files.walk(downloadDir)) {
            List<Path> webmFiles = files
                    .filter(f -> f.toString().toLowerCase().endsWith(".webm"))
                    .toList();

            for (Path webm : webmFiles) {
                String mp3Path = webm.toString().replaceAll("(?i)\\.webm$", ".mp3");
                Path mp3 = Path.of(mp3Path);

                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg", "-y", "-i", webm.toString(),
                        "-codec:a", "libmp3lame", "-b:a", "320k",
                        mp3.toString()
                );
                pb.redirectErrorStream(true);

                Process process = pb.start();
                process.getInputStream().readAllBytes();
                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    log.warn("ffmpeg timed out converting: {}", webm.getFileName());
                    continue;
                }

                int exitCode = process.exitValue();
                if (exitCode == 0) {
                    Files.deleteIfExists(webm);
                    converted.add(mp3.getFileName().toString());
                    log.info("Converted: {} → {}", webm.getFileName(), mp3.getFileName());
                } else {
                    log.warn("ffmpeg failed for {}: exit code {}", webm.getFileName(), exitCode);
                }
            }
        } catch (Exception e) {
            log.error("Format cleanup error: {}", e.getMessage());
        }

        return converted;
    }

    public boolean isFfmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public Path resolveDownloadDir() {
        String dir = config.getDownloadDir();
        if (dir != null && !dir.isBlank()) {
            Path path = Paths.get(dir);
            if (Files.exists(path) && Files.isDirectory(path)) {
                return path;
            }
        }

        Path fallback = Paths.get(System.getProperty("user.home"), "music", "downloads");
        if (Files.exists(fallback) && Files.isDirectory(fallback)) {
            return fallback;
        }

        return null;
    }
}