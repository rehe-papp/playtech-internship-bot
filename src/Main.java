import java.io.*;

public class Main {
    public static void main(String[] args) throws IOException {
        // 1. Initialize budget from command line
        long initialBudget = args.length > 0 ? Long.parseLong(args[0]) : 10000000;

        // 2. Choose Category & Initialize Logic
        String myCategory = "Cooking"; // TODO: Research best category

        BidRequest request = new BidRequest();
        BiddingLogic logic = new BiddingLogic(myCategory, initialBudget);
        System.out.println(logic.getMyCategory());
        System.out.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;

        // 3. The Main Loop
        while ((line = reader.readLine()) != null) {
            char type = line.charAt(0);

            if (type == 'v') {
                // A new auction starts
                request.update(line);
                String bid = logic.decideBid(request);
                System.out.println(bid);
                System.out.flush();

            } else if (type == 'W') {
                // We won
                int cost = Integer.parseInt(line.substring(2).trim());
                logic.handleWin(cost);

            } else if (type == 'L') {
                // We lost
                logic.handleLoss();

            } else if (type == 'S') {
                // Summary every 100 rounds: "S 1289 199"
                String[] parts = line.split(" ");
                int points = Integer.parseInt(parts[1]);
                int spent = Integer.parseInt(parts[2]);
                logic.updateFromSummary(points, spent);
            }
        }
    }
}
