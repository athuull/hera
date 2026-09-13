package com.athuull.hera.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outcome of an audio download job processed by Downtify.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadResult {
    private String jobId;
    private String filename;
    private String status;
    private String errorMessage;
    private Track track;

    public boolean isDone() {
        return "done".equalsIgnoreCase(status);
    }

    public boolean isError() {
        return "error".equalsIgnoreCase(status);
    }
}
