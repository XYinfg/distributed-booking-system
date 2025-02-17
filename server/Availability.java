package server;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Arrays;

public class Availability {
    private boolean[][] weeklyAvailability; // [DayOfWeek (0-6)][Hour (0-23)] - true if available, false if booked

    public Availability() {
        weeklyAvailability = new boolean[7][24];
        for (int i = 0; i < 7; i++) {
            Arrays.fill(weeklyAvailability[i], true); // Initially all slots are available
        }
    }

    public boolean isSlotAvailable(DayOfWeek dayOfWeek, int hour) {
        return weeklyAvailability[dayOfWeek.getValue() - 1][hour];
    }

    public void markBooked(LocalDateTime startTime, LocalDateTime endTime) {
        TimeSlot bookingSlot = new TimeSlot(startTime.getDayOfWeek(), startTime.getHour(), startTime.getMinute(), endTime.getHour(), endTime.getMinute());
        for (int day = 0; day < 7; day++) {
            for (int hour = 0; hour < 24; hour++) {
                TimeSlot slotToCheck = new TimeSlot(DayOfWeek.of(day + 1), hour, 0, hour + 1, 0); // 1-hour slots for simplicity in availability representation
                if (bookingSlot.overlaps(slotToCheck)) {
                    weeklyAvailability[day][hour] = false; // Mark as booked
                }
            }
        }
    }

    public void markAvailable(LocalDateTime startTime, LocalDateTime endTime) { // Reverses markBooked - for Change Booking or Cancellation if needed
        TimeSlot bookingSlot = new TimeSlot(startTime.getDayOfWeek(), startTime.getHour(), startTime.getMinute(), endTime.getHour(), endTime.getMinute());
        for (int day = 0; day < 7; day++) {
            for (int hour = 0; hour < 24; hour++) {
                TimeSlot slotToCheck = new TimeSlot(DayOfWeek.of(day + 1), hour, 0, hour + 1, 0);
                if (bookingSlot.overlaps(slotToCheck)) {
                    weeklyAvailability[day][hour] = true; // Mark as available again
                }
            }
        }
    }

    public boolean[][] getWeeklyAvailability() {
        return weeklyAvailability;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Availability:\n");
        String[] daysOfWeek = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        sb.append("     ");
        for (int hour = 0; hour < 24; hour++) {
            sb.append(String.format("%02d ", hour));
        }
        sb.append("\n");

        for (int day = 0; day < 7; day++) {
            sb.append(daysOfWeek[day]).append(": ");
            for (int hour = 0; hour < 24; hour++) {
                sb.append(weeklyAvailability[day][hour] ? "O " : "X "); // O for Open, X for Booked
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}