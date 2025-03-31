package server;

import java.time.LocalDateTime;
import java.util.UUID;

public class Booking {
    private UUID bookingId;
    private String facilityName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    public Booking(String facilityName, LocalDateTime startTime, LocalDateTime endTime) {
        this.bookingId = UUID.randomUUID(); // Generate unique ID for each booking
        this.facilityName = facilityName;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getConfirmationIdAsString() {
        return bookingId.toString(); // For returning confirmation ID as String to client
    }

    public String getFacilityName() {
        return facilityName;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setStartTime(LocalDateTime starTime) {
        this.startTime = starTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    @Override
    public String toString() {
        return "Booking{" +
                "bookingId=" + bookingId +
                ", facilityName='" + facilityName + '\'' +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                '}';
    }
}