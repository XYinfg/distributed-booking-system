package server;

import java.util.ArrayList;
import java.util.List;

public class Facility {
    private String name;
    private Availability availability;
    private List<Booking> bookings;

    public Facility(String name) {
        this.name = name;
        this.availability = new Availability(); // Initialize with default availability (all slots available)
        this.bookings = new ArrayList<>();
    }

    public String getFacilityName() {
        return name;
    }

    public Availability getAvailability() {
        return availability;
    }

    public List<Booking> getBookings() {
        return bookings;
    }

    public boolean isAvailable(TimeSlot slot) {
        for (Booking booking : bookings) {
            if (booking.conflictsWith(slot)) {
                return false; // Facility is booked during this slot
            }
        }
        return true; // No bookings conflict, facility is available
    }

    public void addBooking(Booking booking) {
        this.bookings.add(booking);
        this.availability.markBooked(booking.getStartTime(), booking.getEndTime()); // Update availability representation
    }

    public void removeBooking(Booking booking) {
        this.bookings.remove(booking);
        this.availability.markAvailable(booking.getStartTime(), booking.getEndTime()); // Update availability representation
    }

    @Override
    public String toString() {
        return "Facility{" +
               "name='" + name + '\'' +
               ", availability=" + availability +
               ", bookings.size=" + bookings.size() +
               '}';
    }
}