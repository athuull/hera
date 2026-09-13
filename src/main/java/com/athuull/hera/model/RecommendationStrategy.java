package com.athuull.hera.model;

/**
 * Enumeration of all supported recommendation discovery algorithms in Hera.
 */
public enum RecommendationStrategy {
    /** Discovers music similar to one or more seed artists. */
    ARTIST_SIMILARITY,

    /** Explores the user's historical top artists, their similar artists, and top tracks. */
    USER_PERSONALIZED,

    /** Discovers music matching the user's recent listening activity and current mood. */
    NOW_LISTENING,

    /** Discovers music based on the user's derived top musical genres and tags. */
    GENRE_BASED,

    /** Discovers music similar to a single specific seed song. */
    TRACK_SIMILARITY,

    /** Discovers music belonging to a specific tag or genre string. */
    TAG_BASED,

    /** Intelligently blends Now-Listening (40%), Personalized (40%), and Genre (20%) algorithms. */
    HYBRID
}