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
        // We do not allow for overnight booking, exception is thrown in request handler before parsing into lower layers
        // Similarly, we do the checking for startTime and endTime in request handler.
        int startDay = startTime.getDayOfWeek().getValue();
        int startHour = startTime.getHour();
        int endDay = endTime.getDayOfWeek().getValue();
        int endHour = endTime.getHour();
        // Note that if user books from say 09:00 to 10:00, both the 09:00 and 10:00 slots will be unavailable
        for (int i = startDay - 1; i < endDay; i++) {
            for (int j = startHour; j <= endHour; j++) {
                weeklyAvailability[i][j] = false;
            }
        }
    }

    public void markAvailable(LocalDateTime startTime, LocalDateTime endTime) { // Reverses markBooked - for Change Booking or Cancellation if needed
        int startDay = startTime.getDayOfWeek().getValue();
        int startHour = startTime.getHour();
        int endDay = endTime.getDayOfWeek().getValue();
        int endHour = endTime.getHour();
        // Note that if user releases slot from 09:00 to 10:00, both the 09:00 and 10:00 slots will become available
        for (int i = startDay - 1; i < endDay; i++) {
            for (int j = startHour; j <= endHour; j++) {
                weeklyAvailability[i][j] = true;
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