package shared;

public enum OperationType {
    QUERY_AVAILABILITY((byte) 1),
    BOOK_FACILITY((byte) 2),
    CHANGE_BOOKING((byte) 3),
    MONITOR_AVAILABILITY((byte) 4),
    GET_SERVER_STATUS((byte) 5), // Idempotent
    EXTEND_BOOKING((byte) 6);     // Non-Idempotent

    private final byte code;

    OperationType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static OperationType fromCode(byte code) {
        for (OperationType type : OperationType.values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown OperationType code: " + code);
    }
}