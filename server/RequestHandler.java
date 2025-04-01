package server;

import server.exceptions.FacilityBookingException;
import shared.MessageHeader;
import shared.constants.ArgumentConstants;
import shared.constants.OperationType;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RequestHandler {
    private final FacilityService facilityService;
    private final MessageService messageService;
    private Map<Integer, byte[]> replyCache;  // Cache last reply for each requestId
    private RequestHistory requestHistory;
    private ArgumentConstants.Semantics semantics;

    public RequestHandler(FacilityService facilityService, MessageService messageService) {
        this.facilityService = facilityService;
        this.messageService = messageService;
        replyCache = new ConcurrentHashMap<>();
        requestHistory = new RequestHistory();
        semantics = ArgumentConstants.Semantics.AT_MOST_ONCE;
    }

    public void setSemantics(ArgumentConstants.Semantics semantics) {
        this.semantics = semantics;
    }

    public void processRequest(byte[] data, InetAddress clientAddr, int clientPort) {
        InetSocketAddress clientAddress = new InetSocketAddress(clientAddr, clientPort);
        MessageHeader header = shared.Marshaller.unmarshalHeader(data);
        int requestId = header.getRequestId();
        OperationType operationType = header.getOperationType();

        System.out.println("Received request from " + clientAddress + ", Request ID: " + requestId + ", Operation: " + operationType);

        byte[] marshalledReply = handleRequest(requestId, operationType, data, clientAddress, semantics);

        if (marshalledReply != null) {
            // TODO: change false placeholder
            messageService.sendMessage(marshalledReply, clientAddr, clientPort, false);
        }
    }

    private byte[] handleRequest(int requestId, OperationType operationType, byte[] data, InetSocketAddress clientAddress, ArgumentConstants.Semantics semantics) {

        if (semantics == ArgumentConstants.Semantics.AT_LEAST_ONCE) {
            if (requestHistory.isDuplicate(requestId)) {
                //  Try to fetch reply from cache
                byte[] cachedReply = replyCache.get(requestId);
                if (cachedReply != null) {
                    System.out.println("Duplicate request ID: " + requestId + ", resending cached reply.");
                    return cachedReply;
                } else {
                    System.out.println("Warning: Duplicate request ID " + requestId + " but no cached reply found. Re-processing.");
                }
            }
        } else {
            // Semantics is at-most-once
            if (requestHistory.isDuplicate(requestId)) {
                System.out.println("Duplicate request ID (At-Most-Once): " + requestId + ", ignoring.");
                return null;
            }
        }

        String errorMessage = null;
        byte[] replyPayload = null;

        try {
            switch (operationType) {
                case QUERY_AVAILABILITY:
                    shared.Marshaller.QueryAvailabilityRequestData queryData = shared.Marshaller.unmarshalQueryAvailabilityRequest(data);
                    replyPayload = handleQueryAvailability(queryData);
                    break;
                case BOOK_FACILITY:
                    shared.Marshaller.BookFacilityRequestData bookData = shared.Marshaller.unmarshalBookFacilityRequest(data);
                    replyPayload = handleBookFacility(bookData);
                    replyCache.clear();
                    break;
                case CHANGE_BOOKING:
                    shared.Marshaller.ChangeBookingRequestData changeData = shared.Marshaller.unmarshalChangeBookingRequest(data);
                    replyPayload = handleChangeBooking(changeData);
                    replyCache.clear();
                    break;
                case MONITOR_AVAILABILITY:
                    shared.Marshaller.MonitorAvailabilityRequestData monitorData = shared.Marshaller.unmarshalMonitorAvailabilityRequest(data);
                    handleMonitorAvailability(monitorData, clientAddress);
                    return null;
                case GET_SERVER_STATUS:
                    replyPayload = handleGetServerStatus();
                    break;
                case EXTEND_BOOKING:
                    shared.Marshaller.ExtendBookingRequestData extendData = shared.Marshaller.unmarshalExtendBookingRequest(data);
                    replyPayload = handleExtendBooking(extendData.getConfirmationId(), extendData.getExtendMinutes());
                    replyCache.clear();
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
            marshalledReply = BookingServer.Marshaller.marshalReply(requestId, operationType, replyPayload);
            replyCache.put(requestId, marshalledReply);
        } else {
            marshalledReply = BookingServer.Marshaller.marshalErrorReply(requestId, operationType, errorMessage != null ? errorMessage : "Unknown error");
            replyCache.remove(requestId);
        }

        requestHistory.addRequestId(requestId);

        return marshalledReply;
    }

    private byte[] handleQueryAvailability(shared.Marshaller.QueryAvailabilityRequestData queryData) {
        String facilityName = queryData.getFacilityName();
        List<DayOfWeek> days = queryData.getDays();
        Facility facility = facilityService.getFacilityByName(facilityName);

        Availability facilityAvailability = facility.getAvailability();
        String availabilityInfo = facilityAvailability.toString(days);

        return availabilityInfo.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] handleBookFacility(shared.Marshaller.BookFacilityRequestData bookData) {
        String facilityName = bookData.getFacilityName();
        LocalDateTime startTime = bookData.getStartTime();
        LocalDateTime endTime = bookData.getEndTime();

        if (startTime.getDayOfWeek() != endTime.getDayOfWeek()) {
            throw new IllegalArgumentException("Invalid booking time: booking cannot be overnight!");
        }

        if (startTime.isAfter(endTime) || startTime.isEqual(endTime)) {
            throw new IllegalArgumentException("Invalid booking time: start time must be before end time.");
        }

        Booking booking = facilityService.bookFacility(facilityName, startTime, endTime);

        // Notify monitoring clients about availability update asynchronously.
        messageService.triggerMonitorUpdates(facilityName);

        return booking.getConfirmationIdAsString().getBytes(StandardCharsets.UTF_8);
    }

    private void handleMonitorAvailability(shared.Marshaller.MonitorAvailabilityRequestData monitorData, InetSocketAddress clientAddress) throws FacilityBookingException {
        String facilityName = monitorData.getFacilityName();
        int monitorIntervalMinutes = monitorData.getMonitorIntervalMinutes();
        Facility facility = facilityService.getFacilityByName(facilityName);

        long expiryTimeMillis = System.currentTimeMillis() + (monitorIntervalMinutes * 60 * 1000L);
        MonitorClient monitorClient = new MonitorClient(clientAddress, facility.getFacilityName(), expiryTimeMillis);
        facilityService.putMonitor(clientAddress, monitorClient);

        System.out.println("Client " + clientAddress + " registered to monitor " + facility.getFacilityName() + " for " + monitorIntervalMinutes + " minutes.");

        // Immediately send the current availability to the newly registered client
        messageService.sendAvailabilityUpdateToMonitor(monitorClient);
    }

    private byte[] handleGetServerStatus() {
        int facilityCount = facilityService.getFacilityCount();
        int bookingCount = facilityService.getBookingCount();
        return ("Server Status: " + facilityCount + " facilities, " + bookingCount + " bookings.").getBytes(StandardCharsets.UTF_8);
    }

    private void editBooking(UUID bookingId, Booking booking, LocalDateTime startTime, LocalDateTime endTime, int startTimeOffsetMinutes, int endTimeOffsetMinutes) {
        LocalDateTime newStartTime = startTime.plusMinutes(startTimeOffsetMinutes);
        LocalDateTime newEndTime = endTime.plusMinutes(endTimeOffsetMinutes);

        if (newStartTime.isBefore(LocalDateTime.now())) {
            throw new FacilityBookingException("Cannot change booking to a time in the past.");
        }
        if (newStartTime.isAfter(newEndTime) || newStartTime.isEqual(newEndTime)) {
            throw new IllegalArgumentException("Invalid booking time after change: start time must be before end time.");
        }

        String facilityName = booking.getFacilityName();

        Facility facility = facilityService.getFacilityByName(facilityName);

        // Temporarily remove the booking to avoid self-conflict during availability check.
        facilityService.markFacilityAvailable(facilityName, startTime, endTime);
        facilityService.removeBooking(bookingId);

        if (!facilityService.checkFacilityAvailability(facilityName, newStartTime, newEndTime)) {
            // Restore the booking if the new slot is not available.
            facility.addBooking(booking);
            throw new FacilityBookingException("Facility '" + facility.getFacilityName() + "' is not available for the changed time.");
        }

        // Update booking times and add back to facility (which updates availability).
        booking.setStartTime(newStartTime);
        booking.setEndTime(newEndTime);
        facility.addBooking(booking);

        // Notify monitoring clients about the update.
        messageService.triggerMonitorUpdates(facilityName);
    }

    private byte[] handleChangeBooking(shared.Marshaller.ChangeBookingRequestData changeData) throws FacilityBookingException, IllegalArgumentException {
        String confirmationIdStr = changeData.getConfirmationId();
        int offsetMinutes = changeData.getOffsetMinutes();
        UUID bookingId;
        try {
            bookingId = UUID.fromString(confirmationIdStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid confirmation ID format.");
        }

        Booking booking = facilityService.getBookingByUUID(bookingId);
        if (booking == null) {
            throw new FacilityBookingException("Booking with confirmation ID '" + confirmationIdStr + "' not found.");
        }

        LocalDateTime originalStartTime = booking.getStartTime();
        LocalDateTime originalEndTime = booking.getEndTime();

        editBooking(bookingId, booking, originalStartTime, originalEndTime, offsetMinutes, offsetMinutes);

        return "Booking changed successfully.".getBytes(StandardCharsets.UTF_8);
    }

    private byte[] handleExtendBooking(String confirmationIdStr, int extendMinutes) throws FacilityBookingException, IllegalArgumentException {
        UUID bookingId;
        try {
            bookingId = UUID.fromString(confirmationIdStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid confirmation ID format.");
        }

        Booking booking = facilityService.getBookingByUUID(bookingId);
        if (booking == null) {
            throw new FacilityBookingException("Booking with confirmation ID '" + confirmationIdStr + "' not found.");
        }

        LocalDateTime originalStartTime = booking.getStartTime();
        LocalDateTime originalEndTime = booking.getEndTime();

        editBooking(bookingId, booking, originalStartTime, originalEndTime, 0, extendMinutes);

        return "Booking extended successfully.".getBytes(StandardCharsets.UTF_8);
    }

}
