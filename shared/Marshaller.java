package shared;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

public class Marshaller {

    // --- Marshalling ---

    public static byte[] marshalHeader(MessageHeader header) {
        ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(header.getRequestId());
        buffer.put(header.getOperationType().getCode());
        buffer.putShort(header.getPayloadLength());
        return buffer.array();
    }

    public static byte[] marshalQueryAvailabilityRequest(int requestId, String facilityName, List<DayOfWeek> days) {
        byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
        int payloadLength = 2 + nameBytes.length + 4 * days.size(); // short (nameLen) + nameBytes + int (day) * days.size();
        ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN); // Header + Payload
        marshalHeaderIntoBuffer(buffer, requestId, OperationType.QUERY_AVAILABILITY, (short) payloadLength);

        buffer.putShort((short) nameBytes.length);
        buffer.put(nameBytes);
        for (DayOfWeek day : days) {
            buffer.putInt(day.getValue()); // Marshal DayOfWeek as int (1-7)
        }
        return buffer.array();
    }

    public static byte[] marshalBookFacilityRequest(int requestId, String facilityName, LocalDateTime startTime, LocalDateTime endTime) {
        byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
        int payloadLength = 2 + nameBytes.length + 3 * 4 + 3 * 4; // short (nameLen) + nameBytes + 3 ints (startTime) + 3 ints (endTime)
        ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
        marshalHeaderIntoBuffer(buffer, requestId, OperationType.BOOK_FACILITY, (short) payloadLength);

        buffer.putShort((short) nameBytes.length);
        buffer.put(nameBytes);
        marshalDateTime(buffer, startTime);
        marshalDateTime(buffer, endTime);
        return buffer.array();
    }

    public static byte[] marshalChangeBookingRequest(int requestId, String confirmationId, int offsetMinutes) {
        byte[] confirmationIdBytes = confirmationId.getBytes(StandardCharsets.UTF_8);
        int payloadLength = 2 + confirmationIdBytes.length + 4; // short (confirmationIdLen) + confirmationIdBytes + int (offsetMinutes)
        ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
        marshalHeaderIntoBuffer(buffer, requestId, OperationType.CHANGE_BOOKING, (short) payloadLength);

        buffer.putShort((short) confirmationIdBytes.length);
        buffer.put(confirmationIdBytes);
        buffer.putInt(offsetMinutes);
        return buffer.array();
    }

    public static byte[] marshalMonitorAvailabilityRequest(int requestId, String facilityName, int monitorIntervalMinutes) {
        byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
        int payloadLength = 2 + nameBytes.length + 4; // short (nameLen) + nameBytes + int (monitorIntervalMinutes)
        ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
        marshalHeaderIntoBuffer(buffer, requestId, OperationType.MONITOR_AVAILABILITY, (short) payloadLength);

        buffer.putShort((short) nameBytes.length);
        buffer.put(nameBytes);
        buffer.putInt(monitorIntervalMinutes);
        return buffer.array();
    }

    public static byte[] marshalGetServerStatusRequest(int requestId) {
        ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN);
        marshalHeaderIntoBuffer(buffer, requestId, OperationType.GET_SERVER_STATUS, (short) 0); // No payload
        return buffer.array();
    }

    public static byte[] marshalExtendBookingRequest(int requestId, String confirmationId, int extendMinutes) {
        byte[] confirmationIdBytes = confirmationId.getBytes(StandardCharsets.UTF_8);
        int payloadLength = 2 + confirmationIdBytes.length + 4; // short (confirmationIdLen) + confirmationIdBytes + int (extendMinutes)
        ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
        marshalHeaderIntoBuffer(buffer, requestId, OperationType.EXTEND_BOOKING, (short) payloadLength);

        buffer.putShort((short) confirmationIdBytes.length);
        buffer.put(confirmationIdBytes);
        buffer.putInt(extendMinutes);
        return buffer.array();
    }

    public static byte[] marshalAvailabilityUpdate(String facilityName, byte[] availabilityData) { // For Monitor updates
        byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
        int payloadLength = 2 + nameBytes.length + availabilityData.length; // short (nameLen) + nameBytes + availabilityData
        ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(-1); // Request ID -1 for server-initiated callbacks (Monitor Updates)
        buffer.put(OperationType.MONITOR_AVAILABILITY.getCode()); // Re-use MONITOR_AVAILABILITY op code to indicate update
        buffer.putShort((short) payloadLength);
        buffer.putShort((short) nameBytes.length);
        buffer.put(nameBytes);
        buffer.put(availabilityData);
        return buffer.array();
    }


    // --- Unmarshalling ---

    public static MessageHeader unmarshalHeader(byte[] message) {
        ByteBuffer buffer = ByteBuffer.wrap(message, 0, 7).order(ByteOrder.BIG_ENDIAN); // Header is always first 7 bytes
        int requestId = buffer.getInt();
        OperationType operationType = OperationType.fromCode(buffer.get());
        short payloadLength = buffer.getShort();
        return new MessageHeader(requestId, operationType, payloadLength);
    }

    public static QueryAvailabilityRequestData unmarshalQueryAvailabilityRequest(byte[] message) {
        ByteBuffer buffer = ByteBuffer.wrap(message, 7, message.length - 7).order(ByteOrder.BIG_ENDIAN); // Skip header
        short nameLength = buffer.getShort();
        byte[] nameBytes = new byte[nameLength];
        buffer.get(nameBytes);
        String facilityName = new String(nameBytes, StandardCharsets.UTF_8);

        List<DayOfWeek> days = new ArrayList<>();
        while (buffer.hasRemaining()) { // Read remaining bytes as days
            days.add(DayOfWeek.of(buffer.getInt()));
        }
        return new QueryAvailabilityRequestData(facilityName, days);
    }

    public static BookFacilityRequestData unmarshalBookFacilityRequest(byte[] message) {
        ByteBuffer buffer = ByteBuffer.wrap(message, 7, message.length - 7).order(ByteOrder.BIG_ENDIAN);
        short nameLength = buffer.getShort();
        byte[] nameBytes = new byte[nameLength];
        buffer.get(nameBytes);
        String facilityName = new String(nameBytes, StandardCharsets.UTF_8);

        LocalDateTime startTime = unmarshalDateTime(buffer);
        LocalDateTime endTime = unmarshalDateTime(buffer);
        return new BookFacilityRequestData(facilityName, startTime, endTime);
    }

    public static ChangeBookingRequestData unmarshalChangeBookingRequest(byte[] message) {
        ByteBuffer buffer = ByteBuffer.wrap(message, 7, message.length - 7).order(ByteOrder.BIG_ENDIAN);
        short confirmationIdLength = buffer.getShort();
        byte[] confirmationIdBytes = new byte[confirmationIdLength];
        buffer.get(confirmationIdBytes);
        String confirmationId = new String(confirmationIdBytes, StandardCharsets.UTF_8);
        int offsetMinutes = buffer.getInt();
        return new ChangeBookingRequestData(confirmationId, offsetMinutes);
    }

    public static MonitorAvailabilityRequestData unmarshalMonitorAvailabilityRequest(byte[] message) {
        ByteBuffer buffer = ByteBuffer.wrap(message, 7, message.length - 7).order(ByteOrder.BIG_ENDIAN);
        short nameLength = buffer.getShort();
        byte[] nameBytes = new byte[nameLength];
        buffer.get(nameBytes);
        String facilityName = new String(nameBytes, StandardCharsets.UTF_8);
        int monitorIntervalMinutes = buffer.getInt();
        return new MonitorAvailabilityRequestData(facilityName, monitorIntervalMinutes);
    }

    public static ExtendBookingRequestData unmarshalExtendBookingRequest(byte[] message) {
        ByteBuffer buffer = ByteBuffer.wrap(message, 7, message.length - 7).order(ByteOrder.BIG_ENDIAN);
        short confirmationIdLength = buffer.getShort();
        byte[] confirmationIdBytes = new byte[confirmationIdLength];
        buffer.get(confirmationIdBytes);
        String confirmationId = new String(confirmationIdBytes, StandardCharsets.UTF_8);
        int extendMinutes = buffer.getInt();
        return new ExtendBookingRequestData(confirmationId, extendMinutes);
    }


    // --- Helper Marshalling/Unmarshalling Methods ---

    private static void marshalHeaderIntoBuffer(ByteBuffer buffer, int requestId, OperationType operationType, short payloadLength) {
        buffer.putInt(requestId);
        buffer.put(operationType.getCode());
        buffer.putShort(payloadLength);
    }

    private static void marshalDateTime(ByteBuffer buffer, LocalDateTime dateTime) {
        buffer.putInt(dateTime.getDayOfWeek().getValue()); // DayOfWeek as int (1-7)
        buffer.putInt(dateTime.getHour());
        buffer.putInt(dateTime.getMinute());
    }

    private static LocalDateTime unmarshalDateTime(ByteBuffer buffer) {
    DayOfWeek dayOfWeek = DayOfWeek.of(buffer.getInt());
    int hour = buffer.getInt();
    int minute = buffer.getInt();
    
    // Create a base LocalDateTime using today's date at the specified time
    LocalDateTime base = LocalDateTime.now().withHour(hour).withMinute(minute).withSecond(0).withNano(0);
    // Adjust to the next or same occurrence of the given day
    base = base.with(TemporalAdjusters.nextOrSame(dayOfWeek));
    return base;
    }


    // --- Data Holder Classes for Unmarshalled Data ---

    public static class QueryAvailabilityRequestData {
        private final String facilityName;
        private final List<DayOfWeek> days;

        public QueryAvailabilityRequestData(String facilityName, List<DayOfWeek> days) {
            this.facilityName = facilityName;
            this.days = days;
        }

        public String getFacilityName() {
            return facilityName;
        }

        public List<DayOfWeek> getDays() {
            return days;
        }
    }

    public static class BookFacilityRequestData {
        private final String facilityName;
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;

        public BookFacilityRequestData(String facilityName, LocalDateTime startTime, LocalDateTime endTime) {
            this.facilityName = facilityName;
            this.startTime = startTime;
            this.endTime = endTime;
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
    }

    public static class ChangeBookingRequestData {
        private final String confirmationId;
        private final int offsetMinutes;

        public ChangeBookingRequestData(String confirmationId, int offsetMinutes) {
            this.confirmationId = confirmationId;
            this.offsetMinutes = offsetMinutes;
        }

        public String getConfirmationId() {
            return confirmationId;
        }

        public int getOffsetMinutes() {
            return offsetMinutes;
        }
    }

    public static class MonitorAvailabilityRequestData {
        private final String facilityName;
        private final int monitorIntervalMinutes;

        public MonitorAvailabilityRequestData(String facilityName, int monitorIntervalMinutes) {
            this.facilityName = facilityName;
            this.monitorIntervalMinutes = monitorIntervalMinutes;
        }

        public String getFacilityName() {
            return facilityName;
        }

        public int getMonitorIntervalMinutes() {
            return monitorIntervalMinutes;
        }
    }

    public static class ExtendBookingRequestData {
        private final String confirmationId;
        private final int extendMinutes;

        public ExtendBookingRequestData(String confirmationId, int extendMinutes) {
            this.confirmationId = confirmationId;
            this.extendMinutes = extendMinutes;
        }

        public String getConfirmationId() {
            return confirmationId;
        }

        public int getExtendMinutes() {
            return extendMinutes;
        }
    }
}