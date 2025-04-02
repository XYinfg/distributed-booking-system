package server;

import shared.constants.ArgumentConstants;
import shared.constants.ProtocolConstants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Arrays;

public class BookingServer {

    private DatagramSocket socket;
    private ArgumentConstants.Semantics semantics;

    private final RequestHandler requestHandler;
    private final FacilityService facilityService;
    private final MessageService messageService;

    byte[] buffer = new byte[ProtocolConstants.MAX_MESSAGE_SIZE];

    public static void main(String[] args) {
        BookingServer server = new BookingServer();
        int port = ProtocolConstants.SERVER_PORT; // Default port
        String semanticsArg = null;

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
            System.out.println("No semantics provided.");
            System.out.println("Default to at-most-once semantics");
            server.semantics = ArgumentConstants.Semantics.AT_MOST_ONCE;
        }
        server.requestHandler.setSemantics(server.semantics);
        System.out.println("Server started with " + server.semantics.getValue() + " semantics.");

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
}
