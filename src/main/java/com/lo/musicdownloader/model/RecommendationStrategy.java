package com.lo.musicdownloader.model;

public enum RecommendationStrategy {
    ARTIST_SIMILARITY,
    USER_PERSONALIZED,      // Top artists → similar artists → their top tracks
    NOW_LISTENING,          // Recent tracks → track.getSimilar (current mood)
    GENRE_BASED,            // Top tags → tag.getTopTracks (genre discovery)
    TRACK_SIMILARITY,
    TAG_BASED,
    HYBRID                  // Blends USER_PERSONALIZED + NOW_LISTENING + GENRE_BASED
}