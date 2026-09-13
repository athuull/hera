package com.athuull.hera.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data transfer object encapsulating a single song recommendation, its origin, match score, and status.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Recommendation {
    private Track track;
    private String sourceArtist;
    private double matchScore;
    private int listenerCount;
    private boolean downloaded;
    private boolean skipped;
    private String skipReason;
}
