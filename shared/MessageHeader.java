package shared;

public class MessageHeader {
    private int requestId;
    private OperationType operationType;
    private short payloadLength;

    public MessageHeader(int requestId, OperationType operationType, short payloadLength) {
        this.requestId = requestId;
        this.operationType = operationType;
        this.payloadLength = payloadLength;
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

    @Override
    public String toString() {
        return "MessageHeader{" +
               "requestId=" + requestId +
               ", operationType=" + operationType +
               ", payloadLength=" + payloadLength +
               '}';
    }
}