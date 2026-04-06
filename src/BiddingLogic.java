import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class BiddingLogic {
    private static final int LOG_EVERY_N_EVENTS = 25;
    private static final int MAX_SEGMENT_STATS = 12000;
    private static final int MAX_FEATURE_STATS = 12000;
    private static final double MANDATORY_SPEND_RATIO = 0.30;
    private static final double TARGET_WIN_RATE = 0.20;

    private String myCategory;
    private final long totalBudget;
    private long remainingEbucks;
    private int totalPointsWon = 0;
    private int totalSpent = 0;
    private int auctionsSeen = 0;

    private double baseValue = 8.0;
    private double sniperMultiplier = 3.0;
    
    // Dynamic bidding adjustment based on summary efficiency
    private double efficiencyMultiplier = 1.0;
    private double lastSummaryEfficiency = 0.0;
    private double previousSummaryEfficiency = 0.0;
    private int summariesSinceBigJump = 100;
    
    // Loss tracking
    private int consecutiveLosses = 0;
    private int totalLosses = 0;
    private int totalWins = 0;
    private double lossMultiplier = 1.0;
    private int intervalWins = 0;
    private int intervalLosses = 0;

    // Market and exploration learning
    private final Random random = new Random(42);
    private double marketPressureMultiplier = 1.0;
    private double priceDiscoveryMultiplier = 1.0;
    private double clearingPriceEstimate = 0.0;
    private int lastBidValue = 1;
    private String lastSegmentKey = "unknown";
    private int hardBidCap = 30;
    private final int minHardBidCap = 1;
    private final int maxHardBidCap = 60;
    private double spendCatchupMultiplier = 1.0;

    // Adaptive feature weights
    private double adaptiveCategoryWeight = 2.0;
    private double adaptiveInterestWeight = 1.5;
    private double adaptiveSubscribedMultiplier = 1.2;
    private double adaptiveEngagementWeight = 2.5;

    private final Map<String, SegmentStats> segmentStats = new HashMap<>();
    private final Map<String, FeatureStats> featureStats = new HashMap<>();

    private static class SegmentStats {
        int impressions;
        int wins;
        int losses;
        long spend;

        int rounds() {
            return wins + losses;
        }

        double winRate() {
            return rounds() > 0 ? (double) wins / rounds() : 0.0;
        }
    }

    private static class FeatureStats {
        int wins;
        int losses;

        double winRateSmoothed() {
            return (double) (wins + 1) / (wins + losses + 2);
        }
    }

    public BiddingLogic(String category, long initialBudget) {
        this.myCategory = category;
        this.totalBudget = initialBudget;
        this.remainingEbucks = initialBudget;
    }

    public String getMyCategory() {
        return myCategory;
    }

    public String decideBid(BidRequest request) {
        auctionsSeen++;
        pruneStatsIfNeeded();

        // 1. Calculate Interest Match
        boolean isMyCat = request.isMyCategory(myCategory);
        boolean isInterested = request.hasInterest(myCategory);
        boolean highEngagement = request.getEngagement() > 0.02;

        // 2. Base Valuation Logic
        double score = 1.0;
        if (isMyCat) score += adaptiveCategoryWeight;
        if (isInterested) score += adaptiveInterestWeight;
        if (request.subscribed) score *= adaptiveSubscribedMultiplier;

        // 3. Pareto/Engagement Factor
        double engagement = request.getEngagement();
        if (highEngagement) score *= adaptiveEngagementWeight;

        // 4. Smooth budget pacing against a target trajectory
        double spendRatio = (double) totalSpent / totalBudget;
        double progressRatio = Math.min(1.0, (double) auctionsSeen / 500000.0); // Estimate 500k rounds
        // Start near 0 and ramp smoothly; avoids forcing heavy early spend.
        double targetSpendRatio = Math.pow(progressRatio, 1.20);
        double pacingError = targetSpendRatio - spendRatio;
        double pacingMultiplier = clamp(1.0 + (pacingError * 2.4), 0.5, 1.5);

        // Separate mandatory spend trajectory for "must spend 30%" requirement.
        double mandatorySpendByNow = MANDATORY_SPEND_RATIO * progressRatio;
        double mandatoryGap = Math.max(0.0, mandatorySpendByNow - spendRatio);

        // 5. Segment learning and exploration
        lastSegmentKey = buildSegmentKey(request, isInterested);
        SegmentStats stats = segmentStats.computeIfAbsent(lastSegmentKey, k -> new SegmentStats());
        stats.impressions++;

        double segmentMultiplier = 1.0;
        double segmentWinRateControl = 1.0;
        if (stats.rounds() >= 20) {
            double segmentWinRate = stats.winRate();
            if (segmentWinRate > 0.18) segmentMultiplier = 1.15;
            else if (segmentWinRate < 0.08) segmentMultiplier = 0.88;

            // If a segment is winning too often, lower bidding pressure for that segment.
            if (segmentWinRate > 0.30) {
                segmentWinRateControl = 0.80;
            } else if (segmentWinRate > 0.22) {
                segmentWinRateControl = 0.90;
            } else if (segmentWinRate < 0.10) {
                segmentWinRateControl = 1.05;
            }

            // In competitive phases, keep segment suppression milder.
            if ((marketPressureMultiplier > 1.25 || consecutiveLosses >= 6) && segmentWinRateControl < 0.95) {
                segmentWinRateControl = 0.95;
            }
        }

        double explorationRate = clamp(0.06 - progressRatio * 0.04, 0.02, 0.06);
        boolean exploring = random.nextDouble() < explorationRate;
        double explorationMultiplier = exploring ? (stats.impressions < 8 ? 1.20 : 1.10) : 1.0;

        int totalRounds = totalWins + totalLosses;
        double currentWinRate = (totalRounds > 0) ? (double) totalWins / totalRounds : 0.0;
        int intervalRounds = intervalWins + intervalLosses;
        double intervalWinRate = (intervalRounds > 0) ? (double) intervalWins / intervalRounds : 0.0;
        boolean competitiveMarket = (currentWinRate < TARGET_WIN_RATE * 0.85 && consecutiveLosses >= 6)
            || marketPressureMultiplier > 1.25;
        double winRateControl = clamp(1.0 + ((TARGET_WIN_RATE - currentWinRate) * 3.0), 0.80, 1.20);
        if (currentWinRate < 0.12) {
            winRateControl = Math.max(winRateControl, 1.15);
        } else if (currentWinRate > 0.25) {
            winRateControl = Math.min(winRateControl, 0.85);
        } else if (currentWinRate > 0.20) {
            winRateControl = Math.min(winRateControl, 0.92);
        }

        double intervalWinRateControl = clamp(1.0 + ((TARGET_WIN_RATE - intervalWinRate) * 4.0), 0.80, 1.20);
        if (intervalRounds >= 20) {
            if (intervalWinRate < 0.12) {
                intervalWinRateControl = Math.max(intervalWinRateControl, 1.12);
            } else if (intervalWinRate > 0.25) {
                intervalWinRateControl = Math.min(intervalWinRateControl, 0.82);
            } else if (intervalWinRate > 0.5) {
                intervalWinRateControl = Math.min(intervalWinRateControl, 0.5);
            } else if (intervalWinRate > 0.75) {
                intervalWinRateControl = Math.min(intervalWinRateControl, 0.10);
            }
        }

        // In highly competitive phases, avoid over-suppressing bids from interval control.
        if (competitiveMarket) {
            intervalWinRateControl = Math.max(intervalWinRateControl, 0.98);
            if (intervalWinRate < TARGET_WIN_RATE) {
                intervalWinRateControl = Math.max(intervalWinRateControl, 1.05);
            }
        }

        // If we are behind mandatory spend and not winning enough, push bids up.
        if (currentWinRate < 0.45) {
            spendCatchupMultiplier = clamp(1.0 + mandatoryGap * 8.0, 1.0, 2.4);
        } else {
            spendCatchupMultiplier = clamp(1.0 + mandatoryGap * 4.0, 1.0, 1.8);
        }

        // Extra bounded push in competitive markets when win rate is under target.
        double competitionBoost = 1.0;
        if (competitiveMarket) {
            double winGap = Math.max(0.0, TARGET_WIN_RATE - currentWinRate);
            competitionBoost = clamp(1.0 + winGap * 2.2 + mandatoryGap * 1.5, 1.0, 1.35);
        }

        // 6. Final Bid Calculation
        // Combine pressure multipliers in a damped way to avoid explosive raw bids.
        double controlMultiplier = clamp(pacingMultiplier * efficiencyMultiplier, 0.40, 1.80);
        double pressureMultiplier = 1.0
            + (lossMultiplier - 1.0) * 0.45
            + (marketPressureMultiplier - 1.0) * 0.35
            + (priceDiscoveryMultiplier - 1.0) * 0.25
            + (spendCatchupMultiplier - 1.0) * 0.30
            + (competitionBoost - 1.0) * 0.35;
        pressureMultiplier = clamp(pressureMultiplier, 0.60, 2.20);

        double behaviorMultiplier = segmentMultiplier * segmentWinRateControl
            * explorationMultiplier * winRateControl * intervalWinRateControl;
        behaviorMultiplier = clamp(behaviorMultiplier, 0.60, 1.60);

        double totalMultiplier = clamp(controlMultiplier * pressureMultiplier * behaviorMultiplier, 0.35, 3.20);
        int bidValue = (int) Math.round(baseValue * score * totalMultiplier);
        int calculatedBidValue = bidValue;

        // Keep a soft floor only during prolonged underbidding.
        if (clearingPriceEstimate > 0 && (currentWinRate < 0.25 || consecutiveLosses >= 10)) {
            int marketFloor = (int) Math.round(clearingPriceEstimate * 0.35);
            if (bidValue < marketFloor) bidValue = marketFloor;
        }

        int valueCap = clampInt((int) Math.round(6 + score * 3), 8, 35);
        int efficiencyCap = clampInt((int) Math.round(22 * efficiencyMultiplier), 6, 30);

        if (consecutiveLosses >= 10000) { 
            bidValue += 10; 
            valueCap += 10; 
            efficiencyCap += 10;
            calculatedBidValue = bidValue; 
        }
        int baseFinalCap = Math.min(hardBidCap, Math.min(valueCap, efficiencyCap));
        int finalBidCap = baseFinalCap;
        String capDebug = "valueCap=" + valueCap + " effCap=" + efficiencyCap + " baseCap=" + baseFinalCap;
        
        // Snipe high-value targets: full on perfect matches, half on partial matches.
        if (isMyCat && isInterested) {
            bidValue *= sniperMultiplier;
            finalBidCap *= sniperMultiplier;
        } else if (isMyCat || isInterested) {
            double halfSniperMultiplier = Math.max(2.0, sniperMultiplier / 2.0);
            bidValue *= halfSniperMultiplier;
            finalBidCap *= halfSniperMultiplier;
        }

        // Hard anti-burn caps: keep bids in realistic ranges for low-value inventory.
        
        if (!isMyCat && !isInterested) {
            finalBidCap = Math.min(finalBidCap, 8);
            capDebug += " (notMyInterest->8)";
        }

        // Recovery override: if a bot is stuck losing, temporarily relax caps.
        // This helps break out of local minima while still bounded by hardBidCap.
        if (consecutiveLosses >= 15 && currentWinRate < 0.12) {
            int recoveryLift = clampInt(2 + ((consecutiveLosses - 10) / 2), 2, maxHardBidCap);
            int recoveryCap = Math.min(hardBidCap, baseFinalCap + recoveryLift);
            if (!isMyCat && !isInterested) {
                recoveryCap = Math.min(recoveryCap, 20);
            }
            finalBidCap = Math.max(finalBidCap, recoveryCap);
            capDebug += " recovery_lift=" + recoveryLift + "->cap=" + recoveryCap;
        }

        // Catch-up cap relaxation when mandatory spend is lagging in competitive markets.
        if (mandatoryGap > 0.01 && (currentWinRate < 0.40 || consecutiveLosses >= 8)) {
            int catchupLift = clampInt((int) Math.round(mandatoryGap * 100), 2, 18);
            int catchupCap = Math.min(hardBidCap, baseFinalCap + catchupLift);
            finalBidCap = Math.max(finalBidCap, catchupCap);
            capDebug += " catchup_lift=" + catchupLift + "->cap=" + catchupCap;
        }

        if (bidValue > finalBidCap) bidValue = finalBidCap;

        // 6. Constraints & Formatting
        if (bidValue > remainingEbucks) bidValue = (int) remainingEbucks;
        if (bidValue < 1) bidValue = 1;

        lastBidValue = bidValue;

        // Bidding Start == Half of Max
        int startingBid = Math.max(1, bidValue / 2);
        return startingBid + " " + bidValue;
    }

    public void handleWin(int cost) {
        this.remainingEbucks -= cost;
        this.totalSpent += cost;
        this.totalWins++;
        this.intervalWins++;

        int totalRounds = totalWins + totalLosses;
        double winRate = (totalRounds > 0) ? (double) totalWins / totalRounds * 100.0 : 0.0;

        updateMarketEstimateOnWin(cost);
        marketPressureMultiplier = Math.max(marketPressureMultiplier * 0.99, 0.9);

        // Search downward for the lowest bid that still wins.
        // Strong win rates should push bids down until the market starts pushing back.
        if (winRate > 65.0 && consecutiveLosses == 0) {
            priceDiscoveryMultiplier = Math.max(priceDiscoveryMultiplier * 0.985, 0.55);
        } else if (cost < clearingPriceEstimate * 0.9) {
            priceDiscoveryMultiplier = Math.max(priceDiscoveryMultiplier * 0.992, 0.55);
        }
        recordAuctionOutcome(true, cost);
        
        // Reset consecutive losses on a win - bring multiplier back down to normal
        if (consecutiveLosses > 0) {
            consecutiveLosses = 0;
            // After a win, reduce the loss multiplier back toward 1.0
            lossMultiplier = Math.max(lossMultiplier * 0.95, 1.0);
        }
    }

    public void handleLoss() {
        this.totalLosses++;
        this.consecutiveLosses++;
        this.intervalLosses++;

        updateMarketEstimateOnLoss();
        marketPressureMultiplier = Math.min(marketPressureMultiplier * 1.02, 1.6);
        priceDiscoveryMultiplier = Math.min(priceDiscoveryMultiplier * 1.06, 1.20);
        adjustHardBidCapOnLoss();

        recordAuctionOutcome(false, 0);
        
        // Adjust bidding based on consecutive losses - BID MORE to recover
        if (consecutiveLosses == 5) {
            // 5 losses in a row - increase bids to win back
            lossMultiplier *= 1.10;
        } else if (consecutiveLosses == 10) {
            // 10 losses in a row - further increase
            lossMultiplier *= 1.15;
        } else if (consecutiveLosses > 15) {
            // Too many losses - aggressive push back
            lossMultiplier = Math.min(lossMultiplier * 1.15, 2.5);
        }
    }

    public void updateFromSummary(int points, int spent) {
        this.totalPointsWon += points;
        
        // Calculate efficiency for this 100-round batch
        double batchEfficiency = (spent > 0) ? (double) points / spent : 0.0;
        double overallEfficiency = (this.totalSpent > 0) ? (double) this.totalPointsWon / this.totalSpent : 0.0;
        
        // Determine adjustment: compare batch efficiency to overall efficiency
        double efficiencyRatio = (overallEfficiency > 0) ? batchEfficiency / overallEfficiency : 1.0;
        
        // Trend detection: is efficiency improving or declining?
        double trend = 1.0;
        if (lastSummaryEfficiency > 0) {
            trend = batchEfficiency / lastSummaryEfficiency;
        }
        
        // Tie efficiencyMultiplier to both relative and absolute efficiency.
        // This avoids overbidding when ratio is slightly above 1.0 but absolute efficiency is still weak.
        double ratioForControl = clamp(efficiencyRatio, 0.60, 1.30);
        double ratioFactor = Math.pow(ratioForControl, 0.7);

        // Absolute efficiency target: if batch efficiency is below this level, bids are damped.
        double absoluteEfficiencyTarget = 0.18;
        double absoluteFactor = clamp(batchEfficiency / absoluteEfficiencyTarget, 0.35, 1.10);

        double targetEfficiencyMultiplier = clamp(ratioFactor * absoluteFactor, 0.25, 1.25);
        efficiencyMultiplier = clamp((efficiencyMultiplier * 0.55) + (targetEfficiencyMultiplier * 0.45), 0.25, 1.25);

        // Emergency pacing brake: if spending too fast versus expected progress, reduce aggression.
        double spendRatio = (double) totalSpent / totalBudget;
        double progressRatio = Math.min(1.0, (double) auctionsSeen / 500000.0);
        double targetSpendRatio = Math.pow(progressRatio, 1.20);
        double mandatorySpendByNow = MANDATORY_SPEND_RATIO * progressRatio;
        if (spendRatio > targetSpendRatio + 0.08) {
            efficiencyMultiplier = Math.max(efficiencyMultiplier * 0.90, 0.25);
        }
        summariesSinceBigJump++;
        
        // Calculate win/loss ratio for insight
        int totalRounds = totalWins + totalLosses;
        double winRate = (totalRounds > 0) ? (double) totalWins / totalRounds * 100 : 0.0;
        int intervalRounds = intervalWins + intervalLosses;
        double intervalWinRate = (intervalRounds > 0) ? (double) intervalWins / intervalRounds * 100.0 : 0.0;

        adjustHardBidCapFromSummary(winRate, batchEfficiency, overallEfficiency, spendRatio);
        
        // Store for trend analysis
        previousSummaryEfficiency = lastSummaryEfficiency;
        lastSummaryEfficiency = batchEfficiency;

        refreshAdaptiveWeights();

            // Reset the per-summary window after we have reacted to the latest 100 rounds.
            intervalWins = 0;
            intervalLosses = 0;
    }

    private void recordAuctionOutcome(boolean won, int cost) {
        SegmentStats stats = segmentStats.computeIfAbsent(lastSegmentKey, k -> new SegmentStats());
        if (won) {
            stats.wins++;
            stats.spend += cost;
        } else {
            stats.losses++;
        }

        trackFeature("segment:" + lastSegmentKey, won);
    }

    private void trackFeature(String feature, boolean won) {
        FeatureStats stats = featureStats.computeIfAbsent(feature, key -> new FeatureStats());
        if (won) stats.wins++;
        else stats.losses++;
    }

    private void pruneStatsIfNeeded() {
        if ((auctionsSeen & 2047) != 0) {
            return;
        }
        pruneSegmentStats();
        pruneFeatureStats();
    }

    private void pruneSegmentStats() {
        if (segmentStats.size() <= MAX_SEGMENT_STATS) return;
        int targetSize = MAX_SEGMENT_STATS - (MAX_SEGMENT_STATS / 10);
        Iterator<Map.Entry<String, SegmentStats>> iterator = segmentStats.entrySet().iterator();
        while (segmentStats.size() > targetSize && iterator.hasNext()) {
            Map.Entry<String, SegmentStats> entry = iterator.next();
            SegmentStats stats = entry.getValue();
            if (stats.impressions <= 1 || stats.rounds() == 0) {
                iterator.remove();
            }
        }
    }

    private void pruneFeatureStats() {
        if (featureStats.size() <= MAX_FEATURE_STATS) return;
        int targetSize = MAX_FEATURE_STATS - (MAX_FEATURE_STATS / 10);
        Iterator<Map.Entry<String, FeatureStats>> iterator = featureStats.entrySet().iterator();
        while (featureStats.size() > targetSize && iterator.hasNext()) {
            Map.Entry<String, FeatureStats> entry = iterator.next();
            FeatureStats stats = entry.getValue();
            if (stats.wins + stats.losses <= 1) {
                iterator.remove();
            }
        }
    }

    private void refreshAdaptiveWeights() {
        // Baseline behavioral signals from outcomes observed so far.
        double segmentSignal = getFeatureWinRate("segment:" + lastSegmentKey);
        double momentum = clamp((lastSummaryEfficiency > 0 && previousSummaryEfficiency > 0)
            ? lastSummaryEfficiency / previousSummaryEfficiency : 1.0, 0.8, 1.25);

        adaptiveCategoryWeight = clamp(1.8 + (segmentSignal - 0.5) * 2.2 * momentum, 1.2, 3.8);
        adaptiveInterestWeight = clamp(1.3 + (segmentSignal - 0.5) * 1.8 * momentum, 0.8, 3.2);
        adaptiveSubscribedMultiplier = clamp(1.1 + (segmentSignal - 0.5) * 0.8, 1.0, 1.8);
        adaptiveEngagementWeight = clamp(2.7 * clamp(momentum, 0.9, 1.2), 1.2, 3.5);
    }

    private double getFeatureWinRate(String feature) {
        FeatureStats stats = featureStats.get(feature);
        if (stats == null) return 0.5;
        return stats.winRateSmoothed();
    }

    private void updateMarketEstimateOnWin(int cost) {
        double observedCost = Math.min(cost, hardBidCap);
        if (clearingPriceEstimate <= 0) {
            clearingPriceEstimate = observedCost;
        } else {
            clearingPriceEstimate = (clearingPriceEstimate * 0.90) + (observedCost * 0.10);
        }
    }

    private void updateMarketEstimateOnLoss() {
        if (lastBidValue > 0) {
            double inferredFloor = Math.min(lastBidValue * 1.005, hardBidCap);
            if (clearingPriceEstimate <= 0) {
                clearingPriceEstimate = inferredFloor;
            } else {
                clearingPriceEstimate = (clearingPriceEstimate * 0.97) + (inferredFloor * 0.03);
            }
        }
    }

    private void adjustHardBidCapOnLoss() {
        if (consecutiveLosses >= 15) {
            hardBidCap = Math.min(hardBidCap + 5, maxHardBidCap);
        } else if (consecutiveLosses >= 10) {
            hardBidCap = Math.min(hardBidCap + 4, maxHardBidCap);
        } else if (consecutiveLosses >= 6) {
            hardBidCap = Math.min(hardBidCap + 3, maxHardBidCap);
        } else if (consecutiveLosses >= 3) {
            hardBidCap = Math.min(hardBidCap + 2, maxHardBidCap);
        }
    }

    private void adjustHardBidCapFromSummary(double winRate, double batchEfficiency, double overallEfficiency, double spendRatio) {
        double progressRatio = Math.min(1.0, (double) auctionsSeen / 500000.0);
        double mandatorySpendByNow = MANDATORY_SPEND_RATIO * progressRatio;
        double mandatoryGap = mandatorySpendByNow - spendRatio;

        boolean competitionLooksHot = (winRate < 40.0 && batchEfficiency <= overallEfficiency * 1.05) || spendRatio > 0.25;
        boolean mustCatchUp = mandatoryGap > 0.02 && winRate < 55.0;

        if (competitionLooksHot || mustCatchUp) {
            int lift = mustCatchUp ? 4 : 2;
            hardBidCap = Math.min(hardBidCap + lift, maxHardBidCap);
        } else if (winRate > 60.0 && batchEfficiency > overallEfficiency * 1.10) {
            hardBidCap = Math.max(hardBidCap - 1, minHardBidCap);
        } else if (winRate > 50.0 && consecutiveLosses == 0) {
            hardBidCap = Math.max(hardBidCap - 1, minHardBidCap);
        }
    }

    private String buildSegmentKey(BidRequest request, boolean isInterested) {
        String category = request.videoCategory == null ? "unknown" : request.videoCategory.toLowerCase(Locale.ROOT);
        return category
            + "|sub:" + request.subscribed
            + "|age:" + request.getAgeBucket()
            + "|eng:" + request.getEngagementBucket()
            + "|int:" + isInterested;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}