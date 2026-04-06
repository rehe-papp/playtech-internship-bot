import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class BidRequest {
    // Video Data
    public String videoCategory;
    public long viewCount;
    public long commentCount;

    // Viewer Data
    public boolean subscribed;
    public String age;
    public String gender;
    public String rawInterests;

    // Derived fields for robust matching
    private String normalizedVideoCategory = "";
    private final Set<String> interestTokens = new HashSet<>();

    /**
     * Updates the fields of this object from the raw input string.
     * This "mutator" approach is memory-efficient.
     */
    public void update(String line) {
        this.videoCategory = "";
        this.viewCount = 0;
        this.commentCount = 0;
        this.subscribed = false;
        this.age = "";
        this.gender = "";
        this.rawInterests = "";

        int cursor = 0;
        int length = line.length();
        while (cursor < length) {
            while (cursor < length && line.charAt(cursor) == ' ') {
                cursor++;
            }
            int eq = line.indexOf('=', cursor);
            if (eq == -1) {
                break;
            }
            int comma = line.indexOf(',', eq + 1);
            if (comma == -1) {
                comma = length;
            }

            String key = line.substring(cursor, eq);
            String value = line.substring(eq + 1, comma);

            switch (key) {
                case "video.category":
                    this.videoCategory = value;
                    break;
                case "video.viewCount":
                    this.viewCount = parseLongSafe(value);
                    break;
                case "video.commentCount":
                    this.commentCount = parseLongSafe(value);
                    break;
                case "viewer.subscribed":
                    this.subscribed = "Y".equals(value);
                    break;
                case "viewer.age":
                    this.age = value;
                    break;
                case "viewer.gender":
                    this.gender = value;
                    break;
                case "viewer.interests":
                    this.rawInterests = value;
                    break;
                default:
                    break;
            }

            cursor = comma + 1;
        }

        this.normalizedVideoCategory = normalize(this.videoCategory);

        interestTokens.clear();
        if (!this.rawInterests.isEmpty()) {
            String[] tokens = this.rawInterests.split("[;|]");
            for (String token : tokens) {
                String normalized = normalize(token);
                if (!normalized.isEmpty()) {
                    interestTokens.add(normalized);
                }
            }
        }
    }

    // --- Fast Helper Methods ---

    private long parseLongSafe(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public double getEngagement() {
        return viewCount == 0 ? 0 : (double) commentCount / viewCount;
    }

    public boolean isMyCategory(String category) {
        return normalizedVideoCategory.equals(normalize(category));
    }

    public boolean hasInterest(String category) {
        return interestTokens.contains(normalize(category));
    }

    public String getAgeBucket() {
        String normalizedAge = normalize(age);
        if (normalizedAge.isEmpty()) return "unknown";
        if (normalizedAge.contains("13") || normalizedAge.contains("17") || normalizedAge.contains("18-24")) return "young";
        if (normalizedAge.contains("25") || normalizedAge.contains("34") || normalizedAge.contains("35-44")) return "adult";
        if (normalizedAge.contains("45") || normalizedAge.contains("54") || normalizedAge.contains("55")) return "senior";
        return normalizedAge;
    }

    public String getEngagementBucket() {
        double engagement = getEngagement();
        if (engagement >= 0.08) return "viral";
        if (engagement >= 0.03) return "high";
        if (engagement >= 0.01) return "medium";
        return "low";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}