package server;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class RequestHistory {
    private Set<Integer> processedRequests = Collections.synchronizedSet(new HashSet<>()); // Thread-safe set

    public boolean isDuplicate(int requestId) {
        return !processedRequests.add(requestId); // add() returns false if element already present
    }

    public void addRequestId(int requestId) {
        processedRequests.add(requestId);
    }

    public void clearHistory() { // Optional: Clear history after some time or for testing
        processedRequests.clear();
    }
}