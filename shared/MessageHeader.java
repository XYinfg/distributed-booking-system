package shared;

import shared.constants.OperationType;

public class MessageHeader {
    private int requestId;
    private OperationType operationType;
    private short payloadLength;
    private boolean simulateLoss;

    public MessageHeader(int requestId, OperationType operationType, short payloadLength, byte simulateLossByte) {
        this.requestId = requestId;
        this.operationType = operationType;
        this.payloadLength = payloadLength;
        this.simulateLoss = simulateLossByte != (byte) 0;
    }

    public int getRequestId() {
        return requestId;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public short getPayloadLength() {
        return payloadLength;
    }

    public boolean getSimulateLoss() {
        return simulateLoss;
    }

    @Override
    public String toString() {
        return "MessageHeader{" +
                "requestId=" + requestId +
                ", operationType=" + operationType +
                ", payloadLength=" + payloadLength +
                '}';
    }
}