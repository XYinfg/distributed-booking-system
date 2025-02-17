package client;

import shared.*;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.util.stream.Collectors;

public class BookingClient {
    private DatagramSocket socket;
    private InetAddress serverAddress;
    private int serverPort;
    private int requestCounter = 0;
    private boolean atLeastOnceSemanticsEnabled = false;
    private double packetLossProbability = 0.0; // Packet loss simulation probability

    public BookingClient(String serverAddressStr, int serverPort) {
        try {
            this.serverAddress = InetAddress.getByName(serverAddressStr);
            this.serverPort = serverPort;
            this.socket = new DatagramSocket();
        } catch (UnknownHostException e) {
            System.err.println("Error: Unknown host: " + serverAddressStr);
            System.exit(1);
        } catch (SocketException e) {
            System.err.println("Error creating socket: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        String serverAddress = "127.0.0.1"; // Default localhost
        int serverPort = ProtocolConstants.SERVER_PORT; // Default port
        String semanticsArg = null;
        String lossProbArg = null;

        for (int i = 0; i < args.length; i++) {
            if ("-server".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                serverAddress = args[i + 1];
                i++;
            } else if ("-port".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try {
                    serverPort = Integer.parseInt(args[i + 1]);
                    i++;
                } catch (NumberFormatException e) {
                    System.err.println("Invalid port number. Using default port: " + serverPort);
                }
            } else if ("-semantics".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                semanticsArg = args[i + 1];
                i++;
            } else if ("-loss".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                lossProbArg = args[i+1];
                i++;
            }
        }

        BookingClient client = new BookingClient(serverAddress, serverPort);

        if ("at-least-once".equalsIgnoreCase(semanticsArg)) {
            client.atLeastOnceSemanticsEnabled = true;
            System.out.println("Client started with At-Least-Once semantics.");
        } else {
            System.out.println("Client started with At-Most-Once semantics (default).");
        }
        if (lossProbArg != null) {
            try {
                client.packetLossProbability = Double.parseDouble(lossProbArg);
                if (client.packetLossProbability < 0 || client.packetLossProbability > 1) {
                    System.err.println("Invalid packet loss probability. Using default: 0.0");
                    client.packetLossProbability = 0.0;
                } else {
                    System.out.println("Simulating packet loss with probability: " + client.packetLossProbability);
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid packet loss probability format. Using default: 0.0");
            }
        }

        client.start();
    }

    private void start() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("Enter command (query, book, change, monitor, status, extend, exit): ");
                String command = scanner.nextLine().trim().toLowerCase();

                if (command.equals("exit")) {
                    System.out.println("Exiting client.");
                    break;
                }

                byte[] request = createRequest(command, scanner);
                if (request != null) {
                    byte[] responseData = sendRequest(request);
                    if (responseData != null) {
                        processResponse(responseData);
                    }
                } else {
                    System.out.println("Invalid command or input.");
                }
            }
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    private boolean simulatePacketLoss() {
        return Math.random() < packetLossProbability;
    }

    private byte[] createRequest(String command, Scanner scanner) {
        requestCounter++;
        int requestId = requestCounter;

        try {
            if (command.startsWith("query")) {
                return createQueryAvailabilityRequest(requestId, command, scanner);
            } else if (command.startsWith("book")) {
                return createBookFacilityRequest(requestId, command, scanner);
            } else if (command.startsWith("change")) {
                return createChangeBookingRequest(requestId, command, scanner);
            } else if (command.startsWith("monitor")) {
                return createMonitorAvailabilityRequest(requestId, command, scanner);
            } else if (command.equals("status")) {
                return Marshaller.marshalGetServerStatusRequest(requestId);
            } else if (command.startsWith("extend")) {
                return createExtendBookingRequest(requestId, command, scanner);
            } else {
                System.out.println("Unknown command.");
                return null;
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Input error: " + e.getMessage());
            return null;
        }
    }

    private byte[] sendRequest(byte[] request) {
        try {
            if (atLeastOnceSemanticsEnabled) {
                return sendWithRetry(request, Marshaller.unmarshalHeader(request).getRequestId());
            } else {
                return sendAtMostOnce(request);
            }
        } catch (IOException e) {
            System.err.println("Network error: " + e.getMessage());
            return null;
        }
    }

    private byte[] sendAtMostOnce(byte[] request) throws IOException {
        DatagramPacket packet = new DatagramPacket(request, request.length, serverAddress, serverPort);

        if (simulatePacketLoss()) {
            System.out.println("[SIMULATED PACKET LOSS - CLIENT SEND]");
            return null;
        }

        socket.send(packet);
        socket.setSoTimeout(5000);
        byte[] buffer = new byte[ProtocolConstants.MAX_MESSAGE_SIZE];
        DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);

        try {
            socket.receive(responsePacket);
            if (simulatePacketLoss()) {
                System.out.println("[SIMULATED PACKET LOSS - CLIENT RECEIVE]");
                return null;
            }
            // Trim the received data to the actual length
            return Arrays.copyOf(responsePacket.getData(), responsePacket.getLength());
        } catch (SocketTimeoutException e) {
            System.out.println("Timeout waiting for server response (At-Most-Once). Request might be lost or server is unavailable.");
            return null;
        } catch (IOException e) {
            System.err.println("Error receiving response: " + e.getMessage());
            return null;
        }
    }

    private byte[] sendWithRetry(byte[] request, int requestId) throws IOException {
        int retries = 3;
        while (retries-- > 0) {
            try {
                DatagramPacket packet = new DatagramPacket(request, request.length, serverAddress, serverPort);
                if (simulatePacketLoss()) {
                    System.out.println("[SIMULATED PACKET LOSS - CLIENT SEND (Retry " + (3 - retries) + ")]");
                    continue;
                }

                socket.send(packet);
                socket.setSoTimeout(2000);
                byte[] buffer = new byte[ProtocolConstants.MAX_MESSAGE_SIZE];
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(responsePacket);
                if (simulatePacketLoss()) {
                    System.out.println("[SIMULATED PACKET LOSS - CLIENT RECEIVE (Retry " + (3 - retries) + ")]");
                    continue;
                }
                return Arrays.copyOf(responsePacket.getData(), responsePacket.getLength());
            } catch (SocketTimeoutException e) {
                System.out.println("Timeout for request ID: " + requestId + ", retry " + (3 - retries) + "...");
                if (retries == 0) {
                    System.out.println("Server unavailable after multiple retries (At-Least-Once).");
                    throw e;
                }
            }
        }
        return null;
    }

    private void processResponse(byte[] responseData) {
        MessageHeader header = Marshaller.unmarshalHeader(responseData);
        OperationType operationType = header.getOperationType();
        short payloadLength = header.getPayloadLength();

        if (payloadLength > 0) {
            // Use exact payload length rather than the entire array
            byte[] payload = Arrays.copyOfRange(responseData, 7, 7 + payloadLength);
            switch (operationType) {
                case QUERY_AVAILABILITY:
                    System.out.println(new String(payload, StandardCharsets.UTF_8));
                    break;
                case BOOK_FACILITY:
                    System.out.println("Booking Confirmation ID: " + new String(payload, StandardCharsets.UTF_8));
                    break;
                case CHANGE_BOOKING:
                    System.out.println(new String(payload, StandardCharsets.UTF_8));
                    break;
                case MONITOR_AVAILABILITY:
                    // Monitor updates are handled separately
                    break;
                case GET_SERVER_STATUS:
                    System.out.println(new String(payload, StandardCharsets.UTF_8));
                    break;
                case EXTEND_BOOKING:
                    System.out.println(new String(payload, StandardCharsets.UTF_8));
                    break;
                default:
                    System.out.println("Server response: " + new String(payload, StandardCharsets.UTF_8));
            }
        } else {
            System.out.println("Server acknowledged operation: " + operationType);
        }
    }

    // --- Request Creation Helpers ---

    private byte[] createQueryAvailabilityRequest(int requestId, String command, Scanner scanner) {
        String[] parts = command.split("\\s+");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Usage: query <facility_name> <day1> <day2> ...");
        }
        String facilityName = parts[1];
        List<DayOfWeek> days = Arrays.stream(parts).skip(2)
                .map(String::toUpperCase)
                .map(DayOfWeek::valueOf)
                .collect(Collectors.toList());

        return Marshaller.marshalQueryAvailabilityRequest(requestId, facilityName, days);
    }

    private byte[] createBookFacilityRequest(int requestId, String command, Scanner scanner) {
        String[] parts = command.split("\\s+");
        // Expecting: book <facility_name> <start_day> <start_time> <end_day> <end_time>
        if (parts.length != 6) {
            throw new IllegalArgumentException("Usage: book <facility_name> <start_day> <start_time> <end_day> <end_time>");
        }
        String facilityName = parts[1];
        LocalDateTime startTime, endTime;
        try {
            startTime = parseDateTime(parts[2] + " " + parts[3]);
            endTime = parseDateTime(parts[4] + " " + parts[5]);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date/time format. Use format like 'MONDAY 09:00'");
        }
        return Marshaller.marshalBookFacilityRequest(requestId, facilityName, startTime, endTime);
    }

    private byte[] createChangeBookingRequest(int requestId, String command, Scanner scanner) {
        String[] parts = command.split("\\s+");
        // Expecting: change <confirmation_id> <offset_minutes>
        if (parts.length != 3) {
            throw new IllegalArgumentException("Usage: change <confirmation_id> <offset_minutes>");
        }
        String confirmationId = parts[1];
        int offsetMinutes;
        try {
            offsetMinutes = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid offset minutes. Must be an integer.");
        }
        return Marshaller.marshalChangeBookingRequest(requestId, confirmationId, offsetMinutes);
    }

    private byte[] createMonitorAvailabilityRequest(int requestId, String command, Scanner scanner) {
        String[] parts = command.split("\\s+");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Usage: monitor <facility_name> <interval_minutes>");
        }
        String facilityName = parts[1];
        int intervalMinutes;
        try {
            intervalMinutes = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid monitor interval minutes. Must be an integer.");
        }
        return Marshaller.marshalMonitorAvailabilityRequest(requestId, facilityName, intervalMinutes);
    }

    private byte[] createExtendBookingRequest(int requestId, String command, Scanner scanner) {
        String[] parts = command.split("\\s+");
        // Expecting: extend <confirmation_id> <extend_minutes>
        if (parts.length != 3) {
            throw new IllegalArgumentException("Usage: extend <confirmation_id> <extend_minutes>");
        }
        String confirmationId = parts[1];
        int extendMinutes;
        try {
            extendMinutes = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid extend minutes. Must be an integer.");
        }
        return Marshaller.marshalExtendBookingRequest(requestId, confirmationId, extendMinutes);
    }

    private LocalDateTime parseDateTime(String dateTimeStr) throws DateTimeParseException {
        String[] parts = dateTimeStr.split("\\s+"); // Expecting: [DAY, TIME]
        if (parts.length != 2) {
            throw new DateTimeParseException("Invalid date time format", dateTimeStr, 0);
        }
        DayOfWeek dayOfWeek = DayOfWeek.valueOf(parts[0].toUpperCase());
        String[] timeParts = parts[1].split(":");
        if (timeParts.length != 2) {
            throw new DateTimeParseException("Invalid time format", parts[1], 0);
        }
        int hour = Integer.parseInt(timeParts[0]);
        int minute = Integer.parseInt(timeParts[1]);
        LocalDateTime base = LocalDateTime.now().withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        base = base.with(TemporalAdjusters.nextOrSame(dayOfWeek));
        return base;
    }
}
