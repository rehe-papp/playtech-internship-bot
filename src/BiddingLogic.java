public class BiddingLogic {
    private final String myCategory;
    private final long totalBudget;
    private long remainingEbucks;
    private int totalPointsWon = 0;
    private int totalSpent = 0;

    public BiddingLogic(String category, long initialBudget) {
        this.myCategory = category;
        this.totalBudget = initialBudget;
        this.remainingEbucks = initialBudget;
    }

    public String decideBid(BidRequest request) {
        // TODO: Logic for point estimation
        // TODO: Logic for budget pacing (hit that 30% floor!)

        int startBid = 5;
        int maxBid = 15;

        // Safety check: don't bid more than you have
        if (maxBid > remainingEbucks) maxBid = (int) remainingEbucks;
        if (startBid > maxBid) startBid = maxBid;

        return startBid + " " + maxBid;
    }

    public void handleWin(int cost) {
        this.remainingEbucks -= cost;
        this.totalSpent += cost;
        // System.err.println("Win! Cost: " + cost + " Left: " + remainingEbucks);
    }

    public void handleLoss() {
        // Optional: Track loss rate
    }

    public void updateFromSummary(int points, int spent) {
        this.totalPointsWon += points;
        // System.err.println("Summary -> Points: " + points + " Spent in interval: " + spent);
    }
}