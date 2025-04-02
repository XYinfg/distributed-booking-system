#ifndef MARSHALLER_H
#define MARSHALLER_H

#include <vector>
#include <string>
#include "Protocol.h"

class Marshaller
{
public:
    // Marshalling functions
    static std::vector<unsigned char> marshalHeader(const MessageHeader &header);
    static std::vector<unsigned char> marshalQueryAvailabilityRequest(int requestId, const std::string &facilityName, const std::vector<DayOfWeek> &days, bool simulateLoss);
    static std::vector<unsigned char> marshalBookFacilityRequest(int requestId, const std::string &facilityName, const DateTime &startTime, const DateTime &endTime, bool simulateLoss);
    static std::vector<unsigned char> marshalChangeBookingRequest(int requestId, const std::string &confirmationId, int offsetMinutes, bool simulateLoss);
    static std::vector<unsigned char> marshalMonitorAvailabilityRequest(int requestId, const std::string &facilityName, int monitorIntervalMinutes, bool simulateLoss);
    static std::vector<unsigned char> marshalGetServerStatusRequest(int requestId, bool simulateLoss);
    static std::vector<unsigned char> marshalExtendBookingRequest(int requestId, const std::string &confirmationId, int extendMinutes, bool simulateLoss);

    // Unmarshalling functions
    static MessageHeader unmarshalHeader(const std::vector<unsigned char> &message);

private:
    // Helper functions
    static void appendInt32(std::vector<unsigned char> &buffer, int32_t value);
    static void appendInt16(std::vector<unsigned char> &buffer, int16_t value);
    static void appendByte(std::vector<unsigned char> &buffer, unsigned char value);
    static void appendString(std::vector<unsigned char> &buffer, const std::string &str);
    static void appendDateTime(std::vector<unsigned char> &buffer, const DateTime &dateTime);

    static int32_t extractInt32(const std::vector<unsigned char> &buffer, size_t offset);
    static int16_t extractInt16(const std::vector<unsigned char> &buffer, size_t offset);
    static unsigned char extractByte(const std::vector<unsigned char> &buffer, size_t offset);
};

#endif // MARSHALLER_H