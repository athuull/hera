package com.athuull.hera.service.strategy;

import com.athuull.hera.model.Recommendation;
import com.athuull.hera.model.RecommendationRequest;
import com.athuull.hera.model.RecommendationStrategy;

import java.util.List;

public interface RecommendationStrategyProvider {
    RecommendationStrategy getStrategy();
    List<Recommendation> getRecommendations(RecommendationRequest request, int limit);
}
