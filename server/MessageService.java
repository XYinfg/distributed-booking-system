package server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MessageService {
    private final FacilityService facilityService;
    private final ExecutorService monitorUpdateExecutor;
    private DatagramSocket socket;

    public MessageService(FacilityService facilityService) {
        this.facilityService = facilityService;
        this.socket = null;
        this.monitorUpdateExecutor = Executors.newSingleThreadExecutor();
    }

    public void setSocket(DatagramSocket socket) {
        this.socket = socket;
    }

    public void sendMessage(byte[] replyMessage, InetAddress clientAddress, int clientPort, boolean simulatePacketLoss) {
        if (simulatePacketLoss) {
            System.out.println("[SIMULATED PACKET LOSS - SERVER SEND]");
            return;
        }
        sendMessage(replyMessage, clientAddress, clientPort);
    }

    public void sendMessage(byte[] replyMessage, InetAddress clientAddress, int clientPort) {
        try {
            DatagramPacket replyPacket = new DatagramPacket(replyMessage, replyMessage.length, clientAddress, clientPort);
            socket.send(replyPacket);
        } catch (IOException e) {
            System.err.println("Error sending reply: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void triggerMonitorUpdates(String facilityName) {
        List<MonitorClient> monitorsToNotify = facilityService.getMonitorsToNotify(facilityName);
        if (!monitorsToNotify.isEmpty()) {
            monitorUpdateExecutor.submit(() -> {
                monitorsToNotify.forEach(this::sendAvailabilityUpdateToMonitor);
            });
        }
    }

    public void sendAvailabilityUpdateToMonitor(MonitorClient monitor) {
        Facility facility = facilityService.getFacilityByName(monitor.getFacilityName());
        // Should never be null
        if (facility != null) {
            byte[] availabilityBytes = facility.getAvailability().toString().getBytes(StandardCharsets.UTF_8);
            byte[] updateMessage = shared.Marshaller.marshalAvailabilityUpdate(monitor.getFacilityName(), availabilityBytes);

            try {
                // TODO: placeholder false now
                sendMessage(updateMessage, monitor.getAddress().getAddress(), monitor.getAddress().getPort(), false);
                System.out.println("Sent availability update for " + monitor.getFacilityName() + " to " + monitor.getAddress());
            } catch (Exception e) {
                System.err.println("Error sending monitor update to " + monitor.getAddress() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void shutdown() {
        monitorUpdateExecutor.shutdown();
        try {
            if (!monitorUpdateExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                monitorUpdateExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            monitorUpdateExecutor.shutdownNow();
        }
    }
}
