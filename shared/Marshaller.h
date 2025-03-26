#ifndef MARSHALLER_H
#define MARSHALLER_H

#include <vector>
#include <string>
#include "Protocol.h"

class Marshaller {
public:
    // Marshalling functions
    static std::vector<byte> marshalHeader(const MessageHeader& header);
    static std::vector<byte> marshalQueryAvailabilityRequest(int requestId, const std::string& facilityName, const std::vector<DayOfWeek>& days);
    static std::vector<byte> marshalBookFacilityRequest(int requestId, const std::string& facilityName, const DateTime& startTime, const DateTime& endTime);
    static std::vector<byte> marshalChangeBookingRequest(int requestId, const std::string& confirmationId, int offsetMinutes);
    static std::vector<byte> marshalMonitorAvailabilityRequest(int requestId, const std::string& facilityName, int monitorIntervalMinutes);
    static std::vector<byte> marshalGetServerStatusRequest(int requestId);
    static std::vector<byte> marshalExtendBookingRequest(int requestId, const std::string& confirmationId, int extendMinutes);
    
    // Unmarshalling functions
    static MessageHeader unmarshalHeader(const std::vector<byte>& message);
    
private:
    // Helper functions
    static void appendInt32(std::vector<byte>& buffer, int32_t value);
    static void appendInt16(std::vector<byte>& buffer, int16_t value);
    static void appendByte(std::vector<byte>& buffer, byte value);
    static void appendString(std::vector<byte>& buffer, const std::string& str);
    static void appendDateTime(std::vector<byte>& buffer, const DateTime& dateTime);
    
    static int32_t extractInt32(const std::vector<byte>& buffer, size_t offset);
    static int16_t extractInt16(const std::vector<byte>& buffer, size_t offset);
    static byte extractByte(const std::vector<byte>& buffer, size_t offset);
};

#endif // MARSHALLER_H