#include "Marshaller.h"
#include <iostream>
#include <cassert>

// Helper functions for byte ordering
uint32_t hostToNetwork32(uint32_t value) {
    return htonl(value);
}

uint16_t hostToNetwork16(uint16_t value) {
    return htons(value);
}

uint32_t networkToHost32(uint32_t value) {
    return ntohl(value);
}

uint16_t networkToHost16(uint16_t value) {
    return ntohs(value);
}

// Marshalling implementations
std::vector<byte> Marshaller::marshalHeader(const MessageHeader& header) {
    std::vector<byte> buffer;
    appendInt32(buffer, header.requestId);
    appendByte(buffer, static_cast<byte>(header.operationType));
    appendInt16(buffer, header.payloadLength);
    return buffer;
}

std::vector<byte> Marshaller::marshalQueryAvailabilityRequest(int requestId, const std::string& facilityName, const std::vector<DayOfWeek>& days) {
    // Calculate payload length: 2 bytes for string length + string bytes + 4 bytes per day
    int16_t payloadLength = 2 + facilityName.size() + 4 * days.size();
    
    // Create header
    MessageHeader header;
    header.requestId = requestId;
    header.operationType = QUERY_AVAILABILITY;
    header.payloadLength = payloadLength;
    
    // Marshal header and payload
    std::vector<byte> buffer = marshalHeader(header);
    
    // Append facility name (with length prefix)
    appendString(buffer, facilityName);
    
    // Append days
    for (const auto& day : days) {
        appendInt32(buffer, static_cast<int32_t>(day));
    }
    
    return buffer;
}

std::vector<byte> Marshaller::marshalBookFacilityRequest(int requestId, const std::string& facilityName, const DateTime& startTime, const DateTime& endTime) {
    // Calculate payload length: 2 bytes for string length + string bytes + 12 bytes for startTime + 12 bytes for endTime
    int16_t payloadLength = 2 + facilityName.size() + 3 * 4 + 3 * 4;
    
    // Create header
    MessageHeader header;
    header.requestId = requestId;
    header.operationType = BOOK_FACILITY;
    header.payloadLength = payloadLength;
    
    // Marshal header and payload
    std::vector<byte> buffer = marshalHeader(header);
    
    // Append facility name (with length prefix)
    appendString(buffer, facilityName);
    
    // Append start and end times
    appendDateTime(buffer, startTime);
    appendDateTime(buffer, endTime);
    
    return buffer;
}

std::vector<byte> Marshaller::marshalChangeBookingRequest(int requestId, const std::string& confirmationId, int offsetMinutes) {
    // Calculate payload length: 2 bytes for string length + string bytes + 4 bytes for offsetMinutes
    int16_t payloadLength = 2 + confirmationId.size() + 4;
    
    // Create header
    MessageHeader header;
    header.requestId = requestId;
    header.operationType = CHANGE_BOOKING;
    header.payloadLength = payloadLength;
    
    // Marshal header and payload
    std::vector<byte> buffer = marshalHeader(header);
    
    // Append confirmation ID (with length prefix)
    appendString(buffer, confirmationId);
    
    // Append offset minutes
    appendInt32(buffer, offsetMinutes);
    
    return buffer;
}

std::vector<byte> Marshaller::marshalMonitorAvailabilityRequest(int requestId, const std::string& facilityName, int monitorIntervalMinutes) {
    // Calculate payload length: 2 bytes for string length + string bytes + 4 bytes for monitorIntervalMinutes
    int16_t payloadLength = 2 + facilityName.size() + 4;
    
    // Create header
    MessageHeader header;
    header.requestId = requestId;
    header.operationType = MONITOR_AVAILABILITY;
    header.payloadLength = payloadLength;
    
    // Marshal header and payload
    std::vector<byte> buffer = marshalHeader(header);
    
    // Append facility name (with length prefix)
    appendString(buffer, facilityName);
    
    // Append monitor interval minutes
    appendInt32(buffer, monitorIntervalMinutes);
    
    return buffer;
}

std::vector<byte> Marshaller::marshalGetServerStatusRequest(int requestId) {
    // No payload for this request
    MessageHeader header;
    header.requestId = requestId;
    header.operationType = GET_SERVER_STATUS;
    header.payloadLength = 0;
    
    return marshalHeader(header);
}

std::vector<byte> Marshaller::marshalExtendBookingRequest(int requestId, const std::string& confirmationId, int extendMinutes) {
    // Calculate payload length: 2 bytes for string length + string bytes + 4 bytes for extendMinutes
    int16_t payloadLength = 2 + confirmationId.size() + 4;
    
    // Create header
    MessageHeader header;
    header.requestId = requestId;
    header.operationType = EXTEND_BOOKING;
    header.payloadLength = payloadLength;
    
    // Marshal header and payload
    std::vector<byte> buffer = marshalHeader(header);
    
    // Append confirmation ID (with length prefix)
    appendString(buffer, confirmationId);
    
    // Append extend minutes
    appendInt32(buffer, extendMinutes);
    
    return buffer;
}

// Unmarshalling implementations
MessageHeader Marshaller::unmarshalHeader(const std::vector<byte>& message) {
    assert(message.size() >= 7); // Header is 7 bytes: 4 for requestId, 1 for operationType, 2 for payloadLength
    
    MessageHeader header;
    header.requestId = extractInt32(message, 0);
    header.operationType = static_cast<OperationType>(extractByte(message, 4));
    header.payloadLength = extractInt16(message, 5);
    
    return header;
}

// Helper functions for appending data to buffers
void Marshaller::appendInt32(std::vector<byte>& buffer, int32_t value) {
    uint32_t netValue = hostToNetwork32(static_cast<uint32_t>(value));
    byte* bytes = reinterpret_cast<byte*>(&netValue);
    buffer.insert(buffer.end(), bytes, bytes + 4);
}

void Marshaller::appendInt16(std::vector<byte>& buffer, int16_t value) {
    uint16_t netValue = hostToNetwork16(static_cast<uint16_t>(value));
    byte* bytes = reinterpret_cast<byte*>(&netValue);
    buffer.insert(buffer.end(), bytes, bytes + 2);
}

void Marshaller::appendByte(std::vector<byte>& buffer, byte value) {
    buffer.push_back(value);
}

void Marshaller::appendString(std::vector<byte>& buffer, const std::string& str) {
    // Append string length (2 bytes)
    appendInt16(buffer, static_cast<int16_t>(str.size()));
    
    // Append string bytes
    buffer.insert(buffer.end(), str.begin(), str.end());
}

void Marshaller::appendDateTime(std::vector<byte>& buffer, const DateTime& dateTime) {
    appendInt32(buffer, static_cast<int32_t>(dateTime.dayOfWeek)); // Day of week (1-7)
    appendInt32(buffer, dateTime.hour);                           // Hour (0-23)
    appendInt32(buffer, dateTime.minute);                         // Minute (0-59)
}

// Helper functions for extracting data from buffers
int32_t Marshaller::extractInt32(const std::vector<byte>& buffer, size_t offset) {
    uint32_t netValue = *reinterpret_cast<const uint32_t*>(&buffer[offset]);
    return static_cast<int32_t>(networkToHost32(netValue));
}

int16_t Marshaller::extractInt16(const std::vector<byte>& buffer, size_t offset) {
    uint16_t netValue = *reinterpret_cast<const uint16_t*>(&buffer[offset]);
    return static_cast<int16_t>(networkToHost16(netValue));
}

byte Marshaller::extractByte(const std::vector<byte>& buffer, size_t offset) {
    return buffer[offset];
}