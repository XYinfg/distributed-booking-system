package server;

import java.net.InetSocketAddress;

public class MonitorClient {
    private InetSocketAddress address;
    private String facilityName;
    private long expiryTimeMillis; // Expiry timestamp in milliseconds

    public MonitorClient(InetSocketAddress address, String facilityName, long expiryTimeMillis) {
        this.address = address;
        this.facilityName = facilityName;
        this.expiryTimeMillis = expiryTimeMillis;
    }

    public InetSocketAddress getAddress() {
        return address;
    }

    public String getFacilityName() {
        return facilityName;
    }

    public long getExpiryTimeMillis() {
        return expiryTimeMillis;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiryTimeMillis;
    }

    @Override
    public String toString() {
        return "MonitorClient{" +
               "address=" + address +
               ", facilityName='" + facilityName + '\'' +
               ", expiryTimeMillis=" + expiryTimeMillis +
               '}';
    }
}