package com.athuull.hera.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationRequest {
    private RecommendationStrategy strategy;
    private List<String> seedArtists;      // Optional manual override
    private String lastfmUsername;         // Primary driver for all personalized strategies
    private String period;                 // overall | 7day | 1month | 3month | 6month | 12month
    private String seedArtist;             // For TRACK_SIMILARITY
    private String seedTrack;              // For TRACK_SIMILARITY
    private String tag;                    // For TAG_BASED
    private Integer limit;
}