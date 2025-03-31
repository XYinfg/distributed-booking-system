package server;

import server.exceptions.FacilityBookingException;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class FacilityService {
    private Map<String, Facility> facilities;
    private Map<UUID, Booking> bookings;
    private Map<InetSocketAddress, MonitorClient> monitors;
    private ScheduledExecutorService monitorExpiryExecutor; // Monitor Expiry Checking Thread

    public FacilityService() {
        this.facilities = initializeFacilities();
        this.bookings = new ConcurrentHashMap<>();
        this.monitors = new ConcurrentHashMap<>();
        this.monitorExpiryExecutor = Executors.newSingleThreadScheduledExecutor();
        monitorExpiryExecutor.scheduleAtFixedRate(
                this::removeExpiredMonitors, 0, 1, java.util.concurrent.TimeUnit.MINUTES
        );
    }

    private Map<String, Facility> initializeFacilities() {
        Map<String, Facility> facilityMap = new HashMap<>();
        facilityMap.put("room101", new Facility("Room101"));
        facilityMap.put("lecturehalla", new Facility("LectureHallA"));
        return facilityMap;
    }

    public Facility getFacilityByName(String facilityName) throws FacilityBookingException {
        Facility facility = facilities.get(facilityName.toLowerCase());
        if (facility == null) {
            throw new FacilityBookingException("Facility '" + facilityName + "' not found.");
        }
        return facility;
    }

    public boolean checkFacilityAvailability(String facilityName, LocalDateTime startTime, LocalDateTime endTime) {
        Facility facility = getFacilityByName(facilityName);
        return facility.isAvailable(startTime, endTime);
    }

    public void markFacilityAvailable(String facilityName, LocalDateTime startTime, LocalDateTime endTime) {
        Facility facility = getFacilityByName(facilityName);
        Availability availability = facility.getAvailability();
        availability.markAvailable(startTime, endTime);
    }

    public Booking bookFacility(String facilityName, LocalDateTime startTime, LocalDateTime endTime) {
        if (checkFacilityAvailability(facilityName, startTime, endTime)) {
            Booking booking = new Booking(facilityName, startTime, endTime);
            getFacilityByName(facilityName).addBooking(booking);
            bookings.put(booking.getBookingId(), booking);
            return booking;
        } else {
            throw new FacilityBookingException("Facility '" + facilityName + "' is not available for the requested time.");
        }
    }

    public Booking getBookingByUUID(UUID bookingId) {
        return bookings.get(bookingId);
    }

    public Booking removeBooking(UUID bookingId) {
        Booking booking = getBookingByUUID(bookingId);
        if (booking != null) {
            Facility facility = getFacilityByName(booking.getFacilityName());
            markFacilityAvailable(booking.getFacilityName(), booking.getStartTime(), booking.getEndTime());
            facility.removeBooking(booking);
        }
        return booking;
    }

    public void putMonitor(InetSocketAddress clientAddress, MonitorClient monitorClient) {
        monitors.put(clientAddress, monitorClient);
    }

    public List<MonitorClient> getMonitorsToNotify(String facilityName) {
        return monitors.values().stream().filter(monitor -> monitor.getFacilityName().equals(facilityName))
                .collect(Collectors.toList());
    }

    public int getFacilityCount() {
        return this.facilities.size();
    }

    public int getBookingCount() {
        return this.bookings.size();
    }

    private void removeExpiredMonitors() {
        monitors.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                System.out.println("Monitor expired and removed: " + entry.getValue());
                return true;
            }
            return false;
        });
    }

    public void shutdown() {
        monitorExpiryExecutor.shutdown();
        try {
            if (!monitorExpiryExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                monitorExpiryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            monitorExpiryExecutor.shutdownNow();
        }
    }
}
