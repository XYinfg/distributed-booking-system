#ifndef PROTOCOL_H
#define PROTOCOL_H

#include <cstdint>
#include <vector>
#include <string>

#ifdef _WIN32
    #include <winsock2.h>
    #include <ws2tcpip.h>
    #pragma comment(lib, "Ws2_32.lib")
    typedef char byte;
#else
    #include <sys/socket.h>
    #include <netinet/in.h>
    #include <arpa/inet.h>
    #include <unistd.h>
    typedef unsigned char byte;
#endif

// Constants matching the Java server
const int SERVER_PORT = 2222;
const int MAX_MESSAGE_SIZE = 1024;

// Operation types matching OperationType.java
enum OperationType {
    QUERY_AVAILABILITY = 1,
    BOOK_FACILITY = 2,
    CHANGE_BOOKING = 3,
    MONITOR_AVAILABILITY = 4,
    GET_SERVER_STATUS = 5,
    EXTEND_BOOKING = 6
};

// Day of week enum matching Java's DayOfWeek
enum DayOfWeek {
    MONDAY = 1,
    TUESDAY = 2,
    WEDNESDAY = 3,
    THURSDAY = 4,
    FRIDAY = 5,
    SATURDAY = 6,
    SUNDAY = 7
};

// Structure for message header matching MessageHeader.java
struct MessageHeader {
    int32_t requestId;
    OperationType operationType;
    int16_t payloadLength;
};

// Structure for date/time representation
struct DateTime {
    DayOfWeek dayOfWeek;
    int hour;
    int minute;
};

#endif // PROTOCOL_H