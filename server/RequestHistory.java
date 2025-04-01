package server;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class RequestHistory {
    private Set<String> processedRequests = Collections.synchronizedSet(new HashSet<>()); // Thread-safe set

    public boolean isDuplicate(String request) {
        return !processedRequests.add(request); // add() returns false if element already present
    }

    public void addRequest(String request) {
        processedRequests.add(request);
    }

    public void clearHistory() { // Optional: Clear history after some time or for testing
        processedRequests.clear();
    }
}