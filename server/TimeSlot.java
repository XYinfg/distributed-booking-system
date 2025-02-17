package server;

import java.time.DayOfWeek;

public class TimeSlot {
    private DayOfWeek dayOfWeek;
    private int startHour;
    private int startMinute;
    private int endHour;
    private int endMinute;

    public TimeSlot(DayOfWeek dayOfWeek, int startHour, int startMinute, int endHour, int endMinute) {
        this.dayOfWeek = dayOfWeek;
        this.startHour = startHour;
        this.startMinute = startMinute;
        this.endHour = endHour;
        this.endMinute = endMinute;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public int getStartHour() {
        return startHour;
    }

    public int getStartMinute() {
        return startMinute;
    }

    public int getEndHour() {
        return endHour;
    }

    public int getEndMinute() {
        return endMinute;
    }

    public boolean overlaps(TimeSlot other) {
        if (this.dayOfWeek != other.dayOfWeek) {
            return false; // Different days, no overlap
        }
        int thisStart = this.startHour * 60 + this.startMinute;
        int thisEnd = this.endHour * 60 + this.endMinute;
        int otherStart = other.startHour * 60 + other.startMinute;
        int otherEnd = other.endHour * 60 + other.endMinute;
        // Check if intervals overlap: startA < endB && startB < endA
        return thisStart < otherEnd && otherStart < thisEnd;
    }
    

    @Override
    public String toString() {
        return "TimeSlot{" +
               "dayOfWeek=" + dayOfWeek +
               ", startHour=" + startHour +
               ", startMinute=" + startMinute +
               ", endHour=" + endHour +
               ", endMinute=" + endMinute +
               '}';
    }
}