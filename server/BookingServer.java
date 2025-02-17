package server;

import shared.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class BookingServer {
    
    private DatagramSocket socket;
    private Map<String, Facility> facilities;
    private Map<UUID, Booking> bookings;
    private Map<InetSocketAddress, MonitorClient> monitors;
    private RequestHistory requestHistory = new RequestHistory();
    private Map<Integer, byte[]> replyCache = new ConcurrentHashMap<>(); // Cache last reply for each requestId
    private boolean atLeastOnceSemanticsEnabled = false; // Default to At-Most-Once
    private double packetLossProbability = 0.0; // Packet loss simulation probability

    private ExecutorService monitorUpdateExecutor = Executors.newSingleThreadExecutor(); // For asynchronous monitor updates

    public static void main(String[] args) {
        BookingServer server = new BookingServer();
        int port = ProtocolConstants.SERVER_PORT; // Default port
        String semanticsArg = null;
        String lossProbArg = null;

        for (int i = 0; i < args.length; i++) {
            if ("-port".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try {
                    port = Integer.parseInt(args[i + 1]);
                    i++; // Skip the next argument (port value)
                } catch (NumberFormatException e) {
                    System.err.println("Invalid port number provided. Using default port: " + port);
                }
            } else if ("-semantics".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                semanticsArg = args[i + 1];
                i++;
            } else if ("-loss".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                lossProbArg = args[i + 1];
                i++;
            }
        }

        if ("at-least-once".equalsIgnoreCase(semanticsArg)) {
            server.atLeastOnceSemanticsEnabled = true;
            System.out.println("Server started with At-Least-Once semantics.");
        } else {
            System.out.println("Server started with At-Most-Once semantics (default).");
        }

        if (lossProbArg != null) {
            try {
                server.packetLossProbability = Double.parseDouble(lossProbArg);
                if (server.packetLossProbability < 0 || server.packetLossProbability > 1) {
                    System.err.println("Invalid packet loss probability. Using default: 0.0");
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
        this.facilities = initializeFacilities();
        this.bookings = new ConcurrentHashMap<>();
        this.monitors = new ConcurrentHashMap<>();
    }

    // Store facilities with lower-case keys
    private Map<String, Facility> initializeFacilities() {
        Map<String, Facility> facilityMap = new HashMap<>();
        facilityMap.put("room101", new Facility("Room101"));
        facilityMap.put("lecturehalla", new Facility("LectureHallA"));
        return facilityMap;
    }

    private void start(int port) {
        try {
            socket = new DatagramSocket(port);
            System.out.println("Server started on port " + port + ", listening for requests...");

            // Monitor Expiry Checking Thread
            Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                    this::removeExpiredMonitors, 0, 1, java.util.concurrent.TimeUnit.MINUTES
            );

            while (true) {
                byte[] buffer = new byte[ProtocolConstants.MAX_MESSAGE_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);

                    if (simulatePacketLoss()) {
                        System.out.println("[SIMULATED PACKET LOSS - SERVER RECEIVE]");
                        continue;
                    }

                    // Trim to the actual received data
                    byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                    processRequest(data, packet.getAddress(), packet.getPort());

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
            monitorUpdateExecutor.shutdown();
        }
    }

    private boolean simulatePacketLoss() {
        return Math.random() < packetLossProbability;
    }

    // Process request using the trimmed data and client address/port
    private void processRequest(byte[] data, InetAddress clientAddr, int clientPort) {
        InetSocketAddress clientAddress = new InetSocketAddress(clientAddr, clientPort);
        MessageHeader header = shared.Marshaller.unmarshalHeader(data);
        int requestId = header.getRequestId();
        OperationType operationType = header.getOperationType();

        System.out.println("Received request from " + clientAddress + ", Request ID: " + requestId + ", Operation: " + operationType);

        if (atLeastOnceSemanticsEnabled) {
            if (requestHistory.isDuplicate(requestId)) {
                byte[] cachedReply = replyCache.get(requestId);
                if (cachedReply != null) {
                    System.out.println("Duplicate request ID: " + requestId + ", resending cached reply.");
                    sendReply(cachedReply, clientAddr, clientPort);
                    return;
                } else {
                    System.out.println("Warning: Duplicate request ID " + requestId + " but no cached reply found. Re-processing.");
                }
            }
        } else {
            if (requestHistory.isDuplicate(requestId)) {
                System.out.println("Duplicate request ID (At-Most-Once): " + requestId + ", ignoring.");
                return;
            }
        }

        byte[] replyPayload = null;
        String errorMessage = null;

        try {
            switch (operationType) {
                case QUERY_AVAILABILITY:
                    shared.Marshaller.QueryAvailabilityRequestData queryData = shared.Marshaller.unmarshalQueryAvailabilityRequest(data);
                    // Convert facility name to lower-case for lookup
                    replyPayload = handleQueryAvailability(queryData.getFacilityName().toLowerCase(), queryData.getDays());
                    break;
                case BOOK_FACILITY:
                    shared.Marshaller.BookFacilityRequestData bookData = shared.Marshaller.unmarshalBookFacilityRequest(data);
                    // Lookup facility in lower-case, and use the canonical name from the stored Facility
                    Facility bookFacility = facilities.get(bookData.getFacilityName().toLowerCase());
                    if (bookFacility == null) {
                        throw new FacilityBookingException("Facility '" + bookData.getFacilityName() + "' not found.");
                    }
                    replyPayload = handleBookFacility(bookFacility.getFacilityName(), bookData.getStartTime(), bookData.getEndTime());
                    break;
                case CHANGE_BOOKING:
                    shared.Marshaller.ChangeBookingRequestData changeData = shared.Marshaller.unmarshalChangeBookingRequest(data);
                    replyPayload = handleChangeBooking(changeData.getConfirmationId(), changeData.getOffsetMinutes());
                    break;
                case MONITOR_AVAILABILITY:
                    shared.Marshaller.MonitorAvailabilityRequestData monitorData = shared.Marshaller.unmarshalMonitorAvailabilityRequest(data);
                    handleMonitorAvailability(monitorData.getFacilityName().toLowerCase(), monitorData.getMonitorIntervalMinutes(), clientAddress);
                    return;
                case GET_SERVER_STATUS:
                    replyPayload = handleGetServerStatus();
                    break;
                case EXTEND_BOOKING:
                    shared.Marshaller.ExtendBookingRequestData extendData = shared.Marshaller.unmarshalExtendBookingRequest(data);
                    replyPayload = handleExtendBooking(extendData.getConfirmationId(), extendData.getExtendMinutes());
                    break;
                default:
                    errorMessage = "Unknown operation type.";
            }
        } catch (IllegalArgumentException e) {
            errorMessage = "Invalid input: " + e.getMessage();
        } catch (FacilityBookingException e) {
            errorMessage = e.getMessage();
        } catch (DateTimeParseException e) {
            errorMessage = "Invalid date/time format.";
        } catch (Exception e) {
            errorMessage = "Server error: " + e.getMessage();
            System.err.println("Unexpected error processing request: " + e.getMessage());
            e.printStackTrace();
        }

        byte[] marshalledReply;
        if (replyPayload != null) {
            marshalledReply = Marshaller.marshalReply(requestId, operationType, replyPayload);
            replyCache.put(requestId, marshalledReply);
        } else {
            marshalledReply = Marshaller.marshalErrorReply(requestId, operationType, errorMessage != null ? errorMessage : "Unknown error");
            replyCache.remove(requestId);
        }

        sendReply(marshalledReply, clientAddr, clientPort);
        requestHistory.addRequestId(requestId);
    }

    private byte[] handleQueryAvailability(String facilityName, List<DayOfWeek> days) throws FacilityBookingException {
        Facility facility = facilities.get(facilityName);
        if (facility == null) {
            throw new FacilityBookingException("Facility '" + facilityName + "' not found.");
        }

        StringBuilder availabilityInfo = new StringBuilder();
        availabilityInfo.append("Availability for ").append(facility.getFacilityName()).append(":\n");
        Availability facilityAvailability = facility.getAvailability();

        for (DayOfWeek day : days) {
            availabilityInfo.append(day).append(":\n");
            boolean[][] weeklyAvailability = facilityAvailability.getWeeklyAvailability();
            availabilityInfo.append("     ");
            for (int hour = 0; hour < 24; hour++) {
                availabilityInfo.append(String.format("%02d ", hour));
            }
            availabilityInfo.append("\n     ");
            for (int hour = 0; hour < 24; hour++) {
                availabilityInfo.append(weeklyAvailability[day.getValue() - 1][hour] ? "O " : "X ");
            }
            availabilityInfo.append("\n");
        }

        return availabilityInfo.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] handleBookFacility(String facilityName, LocalDateTime startTime, LocalDateTime endTime) throws FacilityBookingException {
        if (startTime.isAfter(endTime) || startTime.isEqual(endTime)) {
            throw new IllegalArgumentException("Invalid booking time: start time must be before end time.");
        }
        Facility facility = facilities.get(facilityName.toLowerCase());
        if (facility == null) {
            throw new FacilityBookingException("Facility '" + facilityName + "' not found.");
        }

        TimeSlot bookingSlot = new TimeSlot(startTime.getDayOfWeek(), startTime.getHour(), startTime.getMinute(), endTime.getHour(), endTime.getMinute());
        if (!facility.isAvailable(bookingSlot)) {
            throw new FacilityBookingException("Facility '" + facility.getFacilityName() + "' is not available for the requested time.");
        }

        // Use the canonical facility name from the stored Facility object.
        Booking booking = new Booking(facility.getFacilityName(), startTime, endTime);
        facility.addBooking(booking);
        bookings.put(booking.getBookingId(), booking);

        // Notify monitoring clients about availability update asynchronously.
        triggerMonitorUpdates(facility.getFacilityName());

        return booking.getConfirmationIdAsString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] handleChangeBooking(String confirmationIdStr, int offsetMinutes) throws FacilityBookingException, IllegalArgumentException {
        UUID bookingId;
        try {
            bookingId = UUID.fromString(confirmationIdStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid confirmation ID format.");
        }
    
        Booking booking = bookings.get(bookingId);
        if (booking == null) {
            throw new FacilityBookingException("Booking with confirmation ID '" + confirmationIdStr + "' not found.");
        }
    
        LocalDateTime originalStartTime = booking.getStartTime();
        LocalDateTime originalEndTime = booking.getEndTime();
        LocalDateTime newStartTime = originalStartTime.plusMinutes(offsetMinutes);
        LocalDateTime newEndTime = originalEndTime.plusMinutes(offsetMinutes);
    
        if (newStartTime.isBefore(LocalDateTime.now())) {
            throw new FacilityBookingException("Cannot change booking to a time in the past.");
        }
        if (newStartTime.isAfter(newEndTime) || newStartTime.isEqual(newEndTime)) {
            throw new IllegalArgumentException("Invalid booking time after change: start time must be before end time.");
        }
    
        Facility facility = facilities.get(booking.getFacilityName().toLowerCase());
        if (facility == null) {
            throw new FacilityBookingException("Facility '" + booking.getFacilityName() + "' not found.");
        }
    
        // Temporarily remove the booking to avoid self-conflict during availability check.
        facility.getAvailability().markAvailable(originalStartTime, originalEndTime);
        facility.removeBooking(booking);
    
        TimeSlot newBookingSlot = new TimeSlot(newStartTime.getDayOfWeek(), newStartTime.getHour(), newStartTime.getMinute(),
                                                newEndTime.getHour(), newEndTime.getMinute());
        if (!facility.isAvailable(newBookingSlot)) {
            // Restore the booking if the new slot is not available.
            facility.addBooking(booking);
            throw new FacilityBookingException("Facility '" + facility.getFacilityName() + "' is not available for the changed time.");
        }
    
        // Update booking times and add back to facility (which updates availability).
        booking.setStartTime(newStartTime);
        booking.setEndTime(newEndTime);
        facility.addBooking(booking);
    
        // Notify monitoring clients about the update.
        triggerMonitorUpdates(booking.getFacilityName());
    
        return "Booking changed successfully.".getBytes(StandardCharsets.UTF_8);
    }
    
    
    private void handleMonitorAvailability(String facilityName, int monitorIntervalMinutes, InetSocketAddress clientAddress) throws FacilityBookingException {
        Facility facility = facilities.get(facilityName.toLowerCase());
        if (facility == null) {
            throw new FacilityBookingException("Facility '" + facilityName + "' not found.");
        }
    
        long expiryTimeMillis = System.currentTimeMillis() + (monitorIntervalMinutes * 60 * 1000L);
        MonitorClient monitorClient = new MonitorClient(clientAddress, facility.getFacilityName(), expiryTimeMillis);
        monitors.put(clientAddress, monitorClient);
    
        System.out.println("Client " + clientAddress + " registered to monitor " + facility.getFacilityName() + " for " + monitorIntervalMinutes + " minutes.");
    
        // Immediately send the current availability to the newly registered client
        sendAvailabilityUpdateToMonitor(monitorClient);
    }
    
    private byte[] handleGetServerStatus() {
        return ("Server Status: " + facilities.size() + " facilities, " + bookings.size() + " bookings.").getBytes(StandardCharsets.UTF_8);
    }
    
    private byte[] handleExtendBooking(String confirmationIdStr, int extendMinutes) throws FacilityBookingException, IllegalArgumentException {
        UUID bookingId;
        try {
            bookingId = UUID.fromString(confirmationIdStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid confirmation ID format.");
        }
    
        Booking booking = bookings.get(bookingId);
        if (booking == null) {
            throw new FacilityBookingException("Booking with confirmation ID '" + confirmationIdStr + "' not found.");
        }
    
        LocalDateTime originalEndTime = booking.getEndTime();
        LocalDateTime newEndTime = originalEndTime.plusMinutes(extendMinutes);
    
        Facility facility = facilities.get(booking.getFacilityName().toLowerCase());
        if (facility == null) {
            throw new FacilityBookingException("Facility '" + booking.getFacilityName() + "' not found (This should not happen).");
        }
    
        // Temporarily remove the booking so it doesn't conflict with itself.
        facility.removeBooking(booking);
    
        TimeSlot extendedSlot = new TimeSlot(
                booking.getStartTime().getDayOfWeek(),
                booking.getStartTime().getHour(),
                booking.getStartTime().getMinute(),
                newEndTime.getHour(),
                newEndTime.getMinute()
        );
        if (!facility.isAvailable(extendedSlot)) {
            // Re-add the booking to restore the previous state.
            facility.addBooking(booking);
            throw new FacilityBookingException("Facility '" + facility.getFacilityName() + "' is not fully available for the extended time.");
        }
    
        // Update booking time and re-add to facility.
        booking.setEndTime(newEndTime);
        facility.addBooking(booking);
    
        triggerMonitorUpdates(booking.getFacilityName());
    
        return "Booking extended successfully.".getBytes(StandardCharsets.UTF_8);
    }
    
    
    private void sendReply(byte[] replyMessage, InetAddress clientAddress, int clientPort) {
        try {
            if (simulatePacketLoss()) {
                System.out.println("[SIMULATED PACKET LOSS - SERVER SEND]");
                return;
            }
    
            DatagramPacket replyPacket = new DatagramPacket(replyMessage, replyMessage.length, clientAddress, clientPort);
            socket.send(replyPacket);
        } catch (IOException e) {
            System.err.println("Error sending reply: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void triggerMonitorUpdates(String facilityName) {
        List<MonitorClient> monitorsToNotify = monitors.values().stream()
                .filter(monitor -> monitor.getFacilityName().equals(facilityName))
                .collect(Collectors.toList());
    
        if (!monitorsToNotify.isEmpty()) {
            monitorUpdateExecutor.submit(() -> {
                monitorsToNotify.forEach(this::sendAvailabilityUpdateToMonitor);
            });
        }
    }
    
    private void sendAvailabilityUpdateToMonitor(MonitorClient monitor) {
        Facility facility = facilities.get(monitor.getFacilityName().toLowerCase());
        if (facility != null) {
            byte[] availabilityBytes = facility.getAvailability().toString().getBytes(StandardCharsets.UTF_8);
            byte[] updateMessage = shared.Marshaller.marshalAvailabilityUpdate(monitor.getFacilityName(), availabilityBytes);
    
            try {
                sendReply(updateMessage, monitor.getAddress().getAddress(), monitor.getAddress().getPort());
                System.out.println("Sent availability update for " + monitor.getFacilityName() + " to " + monitor.getAddress());
            } catch (Exception e) {
                System.err.println("Error sending monitor update to " + monitor.getAddress() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
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
    
    // --- Inner Exception Class ---
    public static class FacilityBookingException extends Exception {
        public FacilityBookingException(String message) {
            super(message);
        }
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
