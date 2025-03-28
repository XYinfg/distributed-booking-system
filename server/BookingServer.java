package server;

import shared.MessageHeader;
import shared.constants.ArgumentConstants;
import shared.constants.OperationType;
import shared.constants.ProtocolConstants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BookingServer {

    private DatagramSocket socket;
    private double packetLossProbability = 0.0; // Packet loss simulation probability
    private ArgumentConstants.Semantics semantics;

    private final RequestHandler requestHandler;
    private final FacilityService facilityService;
    private final MessageService messageService;

    byte[] buffer = new byte[ProtocolConstants.MAX_MESSAGE_SIZE];

    public static void main(String[] args) {
        BookingServer server = new BookingServer();
        int port = ProtocolConstants.SERVER_PORT; // Default port
        String semanticsArg = null;
        String lossProbArg = null;

        for (int i = 0; i < args.length; i++) {
            // Skip if no next arg
            if (i + 1 >= args.length) {
                break;
            }
            String arg = args[i];
            switch (arg) {
                case ArgumentConstants.PORT:
                    try {
                        port = Integer.parseInt(args[i + 1]);
                        i++;  // Skip the next argument (port value)
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid port number provided. Using default port: " + port);
                    }
                    break;
                case ArgumentConstants.LOSS:
                    lossProbArg = args[i + 1];
                    i++;  // Skip the next argument (loss value)
                    break;
                case ArgumentConstants.SEMANTICS:
                    semanticsArg = args[i + 1];
                    i++;  // Skip the next argument (semantics value)
                    break;
                default:
                    System.out.println("Invalid argument: " + args[i]);
            }
        }

        if (semanticsArg != null) {
            try {
                server.semantics = ArgumentConstants.Semantics.fromString(semanticsArg);
            } catch (IllegalArgumentException e) {
                System.err.println("Illegal semantics argument: " + semanticsArg);
                System.out.println("Default to at-most-once semantics");
                server.semantics = ArgumentConstants.Semantics.AT_MOST_ONCE;
            }
        } else {
            server.semantics = ArgumentConstants.Semantics.AT_MOST_ONCE;
        }
        System.out.println("Server started with " + server.semantics.getValue() + " semantics.");

        // TODO: refactor to change into boolean function instead
        if (lossProbArg != null) {
            try {
                server.packetLossProbability = Double.parseDouble(lossProbArg);
                if (server.packetLossProbability < 0 || server.packetLossProbability > 1) {
                    System.err.println("Invalid packet loss probability (must be between 0 and 1 inclusive). Using default: 0.0");
                    server.packetLossProbability = 0.0;
                } else {
                    System.out.println("Simulating packet loss with probability: " + server.packetLossProbability);
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid packet loss probability format. Using default: 0.0");
            }
        }

        server.start(port);
    }

    public BookingServer() {
        this.facilityService = new FacilityService();
        this.messageService = new MessageService(this.facilityService);
        this.requestHandler = new RequestHandler(this.facilityService, this.messageService);
    }

    private void start(int port) {
        try {
            socket = new DatagramSocket(port);
            messageService.setSocket(socket);

            System.out.println("Server started on port " + port + ", listening for requests...");

            // Keep running until thread is interrupted
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    // TODO: refactor this
                    if (simulatePacketLoss()) {
                        System.out.println("[SIMULATED PACKET LOSS - SERVER RECEIVE]");
                        continue;
                    }

                    // Trim to the actual received data
                    byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                    requestHandler.processRequest(data, packet.getAddress(), packet.getPort());

                } catch (IOException e) {
                    System.err.println("Error receiving packet: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (SocketException e) {
            System.err.println("Socket error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            facilityService.shutdown();
            messageService.shutdown();
        }
    }

    private boolean simulatePacketLoss() {
        return Math.random() < packetLossProbability;
    }


    // --- Marshaller Helper Methods ---
    public class Marshaller {

        // --- Marshalling ---

        public static byte[] marshalReplyHeader(int requestId, OperationType operationType, short payloadLength) {
            ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(requestId);
            buffer.put(operationType.getCode());
            buffer.putShort(payloadLength);
            return buffer.array();
        }

        public static byte[] marshalReply(int requestId, OperationType operationType, byte[] payload) {
            int payloadLength = payload != null ? payload.length : 0;
            ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
            buffer.put(marshalReplyHeader(requestId, operationType, (short) payloadLength));
            if (payload != null) {
                buffer.put(payload);
            }
            return buffer.array();
        }

        public static byte[] marshalErrorReply(int requestId, OperationType operationType, String errorMessage) {
            byte[] errorBytes = errorMessage.getBytes(StandardCharsets.UTF_8);
            int payloadLength = errorBytes.length;
            ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
            buffer.put(marshalReplyHeader(requestId, operationType, (short) payloadLength));
            buffer.put(errorBytes);
            return buffer.array();
        }

        public static byte[] marshalAvailabilityUpdate(String facilityName, byte[] availabilityData) {
            byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
            int payloadLength = 2 + nameBytes.length + availabilityData.length;
            ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(-1); // Request ID -1 for server-initiated callbacks (Monitor Updates)
            buffer.put(OperationType.MONITOR_AVAILABILITY.getCode());
            buffer.putShort((short) payloadLength);
            buffer.putShort((short) nameBytes.length);
            buffer.put(nameBytes);
            buffer.put(availabilityData);
            return buffer.array();
        }

        public static byte[] marshalHeader(MessageHeader header) {
            ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(header.getRequestId());
            buffer.put(header.getOperationType().getCode());
            buffer.putShort(header.getPayloadLength());
            return buffer.array();
        }

        public static byte[] marshalQueryAvailabilityRequest(int requestId, String facilityName, List<DayOfWeek> days) {
            byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
            int payloadLength = 2 + nameBytes.length + 4 * days.size();
            ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(requestId);
            buffer.put(OperationType.QUERY_AVAILABILITY.getCode());
            buffer.putShort((short) payloadLength);

            buffer.putShort((short) nameBytes.length);
            buffer.put(nameBytes);
            for (DayOfWeek day : days) {
                buffer.putInt(day.getValue());
            }
            return buffer.array();
        }

        public static byte[] marshalBookFacilityRequest(int requestId, String facilityName, LocalDateTime startTime, LocalDateTime endTime) {
            byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
            int payloadLength = 2 + nameBytes.length + 3 * 4 + 3 * 4;
            ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(requestId);
            buffer.put(OperationType.BOOK_FACILITY.getCode());
            buffer.putShort((short) payloadLength);

            buffer.putShort((short) nameBytes.length);
            buffer.put(nameBytes);
            marshalDateTime(buffer, startTime);
            marshalDateTime(buffer, endTime);
            return buffer.array();
        }

        public static byte[] marshalChangeBookingRequest(int requestId, String confirmationId, int offsetMinutes) {
            byte[] confirmationIdBytes = confirmationId.getBytes(StandardCharsets.UTF_8);
            int payloadLength = 2 + confirmationIdBytes.length + 4;
            ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(requestId);
            buffer.put(OperationType.CHANGE_BOOKING.getCode());
            buffer.putShort((short) payloadLength);

            buffer.putShort((short) confirmationIdBytes.length);
            buffer.put(confirmationIdBytes);
            buffer.putInt(offsetMinutes);
            return buffer.array();
        }

        public static byte[] marshalMonitorAvailabilityRequest(int requestId, String facilityName, int monitorIntervalMinutes) {
            byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
            int payloadLength = 2 + nameBytes.length + 4;
            ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(requestId);
            buffer.put(OperationType.MONITOR_AVAILABILITY.getCode());
            buffer.putShort((short) payloadLength);

            buffer.putShort((short) nameBytes.length);
            buffer.put(nameBytes);
            buffer.putInt(monitorIntervalMinutes);
            return buffer.array();
        }

        public static byte[] marshalGetServerStatusRequest(int requestId) {
            ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(requestId);
            buffer.put(OperationType.GET_SERVER_STATUS.getCode());
            buffer.putShort((short) 0);
            return buffer.array();
        }

        public static byte[] marshalExtendBookingRequest(int requestId, String confirmationId, int extendMinutes) {
            byte[] confirmationIdBytes = confirmationId.getBytes(StandardCharsets.UTF_8);
            int payloadLength = 2 + confirmationIdBytes.length + 4;
            ByteBuffer buffer = ByteBuffer.allocate(7 + payloadLength).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(requestId);
            buffer.put(OperationType.EXTEND_BOOKING.getCode());
            buffer.putShort((short) payloadLength);

            buffer.putShort((short) confirmationIdBytes.length);
            buffer.put(confirmationIdBytes);
            buffer.putInt(extendMinutes);
            return buffer.array();
        }


        // --- Unmarshalling ---

        public static MessageHeader unmarshalHeader(byte[] message) {
            ByteBuffer buffer = ByteBuffer.wrap(message, 0, 7).order(ByteOrder.BIG_ENDIAN);
            int requestId = buffer.getInt();
            OperationType operationType = OperationType.fromCode(buffer.get());
            short payloadLength = buffer.getShort();
            return new MessageHeader(requestId, operationType, payloadLength);
        }

        public static QueryAvailabilityRequestData unmarshalQueryAvailabilityRequest(byte[] message) {
            ByteBuffer buffer = ByteBuffer.wrap(message, 7, message.length - 7).order(ByteOrder.BIG_ENDIAN);
            short nameLength = buffer.getShort();
            byte[] nameBytes = new byte[nameLength];
            buffer.get(nameBytes);
            String facilityName = new String(nameBytes, StandardCharsets.UTF_8);

            List<DayOfWeek> days = new ArrayList<>();
            while (buffer.hasRemaining()) {
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


        private static void marshalDateTime(ByteBuffer buffer, LocalDateTime dateTime) {
            buffer.putInt(dateTime.getDayOfWeek().getValue()); // DayOfWeek as int (1-7)
            buffer.putInt(dateTime.getHour());
            buffer.putInt(dateTime.getMinute());
        }

        private static LocalDateTime unmarshalDateTime(ByteBuffer buffer) {
            DayOfWeek dayOfWeek = DayOfWeek.of(buffer.getInt());
            int hour = buffer.getInt();
            int minute = buffer.getInt();
            LocalDateTime base = LocalDateTime.now().withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            base = base.with(java.time.temporal.TemporalAdjusters.nextOrSame(dayOfWeek));
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
}
