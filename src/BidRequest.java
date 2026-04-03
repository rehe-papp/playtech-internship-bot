public class BidRequest {
    // Video Data
    public String videoCategory;
    public long viewCount;
    public long commentCount;

    // Viewer Data
    public boolean subscribed;
    public String age;
    public String gender;
    public String[] interests;

    /**
     * Updates the fields of this object from the raw input string.
     * This "mutator" approach is memory-efficient.
     */
    public void update(String line) {
        this.videoCategory = parseValue(line, "video.category");
        this.viewCount = parseLong(line, "video.viewCount");
        this.commentCount = parseLong(line, "video.commentCount");
        this.subscribed = "Y".equals(parseValue(line, "viewer.subscribed"));
        this.age = parseValue(line, "viewer.age");
        this.gender = parseValue(line, "viewer.gender");

        String rawInterests = parseValue(line, "viewer.interests");
        this.interests = rawInterests.isEmpty() ? new String[0] : rawInterests.split(";");
    }

    // --- Fast Helper Methods ---

    private String parseValue(String data, String key) {
        int start = data.indexOf(key + "=");
        if (start == -1) return "";
        start += key.length() + 1;
        int end = data.indexOf(",", start);
        if (end == -1) end = data.length();
        return data.substring(start, end);
    }

    private long parseLong(String data, String key) {
        String val = parseValue(data, key);
        return val.isEmpty() ? 0 : Long.parseLong(val);
    }

    public double getEngagement() {
        return viewCount == 0 ? 0 : (double) commentCount / viewCount;
    }
}