package com.athuull.hera.service.strategy;

import com.athuull.hera.model.Recommendation;
import com.athuull.hera.model.RecommendationRequest;
import com.athuull.hera.model.RecommendationStrategy;

import java.util.List;

/**
 * Strategy interface defining the contract for all recommendation algorithms in Hera.
 * <p>
 * Implements the <b>Strategy Design Pattern</b>. Spring Boot automatically discovers all
 * implementing classes and registers them into {@link com.athuull.hera.service.RecommendationService}.
 * </p>
 */
public interface RecommendationStrategyProvider {

    /**
     * Identifies the enum strategy type this provider implements.
     */
    RecommendationStrategy getStrategy();

    /**
     * Executes recommendation discovery for the given request and returns ranked candidates.
     *
     * @param request the criteria (username, period, seed artists/tracks/tags)
     * @param limit maximum number of tracks requested
     * @return a list of deduplicated recommendations
     */
    List<Recommendation> getRecommendations(RecommendationRequest request, int limit);
}
