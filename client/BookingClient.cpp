#include <iostream>
#include <cstring>
#include <string>
#include <vector>
#include <chrono>
#include <thread>
#include <random>
#include <algorithm>
#include <ctime>
#include "Protocol.h"
#include "Marshaller.h"

using namespace std;

class BookingClient {
private:
    string serverAddress;
    int serverPort;
    int clientSocket;
    struct sockaddr_in serverAddr;
    int requestCounter = 0;
    bool atLeastOnceSemanticsEnabled = false;
    double packetLossProbability = 0.0;

    bool simulatePacketLoss() {
        return ((double)rand() / RAND_MAX) < packetLossProbability;
    }

    vector<byte> createRequest(const string& command, const vector<string>& args) {
        requestCounter++;
        int requestId = requestCounter;

        try {
            if (command == "query") {
                return createQueryAvailabilityRequest(requestId, args);
            } else if (command == "book") {
                return createBookFacilityRequest(requestId, args);
            } else if (command == "change") {
                return createChangeBookingRequest(requestId, args);
            } else if (command == "monitor") {
                return createMonitorAvailabilityRequest(requestId, args);
            } else if (command == "status") {
                return Marshaller::marshalGetServerStatusRequest(requestId);
            } else if (command == "extend") {
                return createExtendBookingRequest(requestId, args);
            } else {
                cout << "Unknown command." << endl;
                return vector<byte>();
            }
        } catch (const exception& e) {
            cout << "Input error: " << e.what() << endl;
            return vector<byte>();
        }
    }

    vector<byte> sendRequest(const vector<byte>& request) {
        if (request.empty()) return vector<byte>();

        try {
            if (atLeastOnceSemanticsEnabled) {
                MessageHeader header = Marshaller::unmarshalHeader(request);
                return sendWithRetry(request, header.requestId);
            } else {
                return sendAtMostOnce(request);
            }
        } catch (const exception& e) {
            cerr << "Network error: " << e.what() << endl;
            return vector<byte>();
        }
    }

    vector<byte> sendAtMostOnce(const vector<byte>& request) {
        if (simulatePacketLoss()) {
            cout << "[SIMULATED PACKET LOSS - CLIENT SEND]" << endl;
            return vector<byte>();
        }

        // Send the request
        if (sendto(clientSocket, request.data(), request.size(), 0, 
                 (struct sockaddr*)&serverAddr, sizeof(serverAddr)) < 0) {
            cerr << "Failed to send packet" << endl;
            return vector<byte>();
        }

        // Set timeout for receiving response
        struct timeval tv;
        tv.tv_sec = 5;  // 5 seconds timeout
        tv.tv_usec = 0;
        if (setsockopt(clientSocket, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) < 0) {
            cerr << "Failed to set socket timeout" << endl;
            return vector<byte>();
        }

        // Receive response
        vector<byte> buffer(MAX_MESSAGE_SIZE);
        struct sockaddr_in from;
        socklen_t fromlen = sizeof(from);
        
        int received = recvfrom(clientSocket, buffer.data(), buffer.size(), 0, 
                             (struct sockaddr*)&from, &fromlen);
        
        if (received < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                cout << "Timeout waiting for server response (At-Most-Once). "
                     << "Request might be lost or server is unavailable." << endl;
            } else {
                cerr << "Error receiving response: " << strerror(errno) << endl;
            }
            return vector<byte>();
        }

        if (simulatePacketLoss()) {
            cout << "[SIMULATED PACKET LOSS - CLIENT RECEIVE]" << endl;
            return vector<byte>();
        }

        // Resize buffer to actual data received
        buffer.resize(received);
        return buffer;
    }

    vector<byte> sendWithRetry(const vector<byte>& request, int requestId) {
        int retries = 3;
        while (retries-- > 0) {
            if (simulatePacketLoss()) {
                cout << "[SIMULATED PACKET LOSS - CLIENT SEND (Retry " 
                     << (3 - retries) << ")]" << endl;
                continue;
            }

            // Send the request
            if (sendto(clientSocket, request.data(), request.size(), 0, 
                     (struct sockaddr*)&serverAddr, sizeof(serverAddr)) < 0) {
                cerr << "Failed to send packet" << endl;
                continue;
            }

            // Set timeout for receiving response
            struct timeval tv;
            tv.tv_sec = 2;  // 2 seconds timeout
            tv.tv_usec = 0;
            if (setsockopt(clientSocket, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) < 0) {
                cerr << "Failed to set socket timeout" << endl;
                continue;
            }

            // Receive response
            vector<byte> buffer(MAX_MESSAGE_SIZE);
            struct sockaddr_in from;
            socklen_t fromlen = sizeof(from);
            
            int received = recvfrom(clientSocket, buffer.data(), buffer.size(), 0, 
                                 (struct sockaddr*)&from, &fromlen);
            
            if (received < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    cout << "Timeout for request ID: " << requestId << ", retry " 
                         << (3 - retries) << "..." << endl;
                    if (retries == 0) {
                        cout << "Server unavailable after multiple retries (At-Least-Once)." << endl;
                        return vector<byte>();
                    }
                } else {
                    cerr << "Error receiving response: " << strerror(errno) << endl;
                    continue;
                }
            } else {
                if (simulatePacketLoss()) {
                    cout << "[SIMULATED PACKET LOSS - CLIENT RECEIVE (Retry " 
                         << (3 - retries) << ")]" << endl;
                    continue;
                }
                
                // Resize buffer to actual data received
                buffer.resize(received);
                return buffer;
            }
        }
        return vector<byte>();
    }

    void processResponse(const vector<byte>& responseData) {
        if (responseData.empty()) return;

        MessageHeader header = Marshaller::unmarshalHeader(responseData);
        OperationType operationType = header.operationType;
        short payloadLength = header.payloadLength;

        if (payloadLength > 0) {
            // Extract payload bytes
            vector<byte> payload(responseData.begin() + 7, 
                              responseData.begin() + 7 + payloadLength);
            
            // Convert payload to string for display
            string payloadStr(payload.begin(), payload.end());
            
            switch (operationType) {
                case QUERY_AVAILABILITY:
                    cout << payloadStr << endl;
                    break;
                case BOOK_FACILITY:
                    cout << "Booking Confirmation ID: " << payloadStr << endl;
                    break;
                case CHANGE_BOOKING:
                    cout << payloadStr << endl;
                    break;
                case MONITOR_AVAILABILITY:
                    // Monitor updates are handled separately
                    break;
                case GET_SERVER_STATUS:
                    cout << payloadStr << endl;
                    break;
                case EXTEND_BOOKING:
                    cout << payloadStr << endl;
                    break;
                default:
                    cout << "Server response: " << payloadStr << endl;
            }
        } else {
            cout << "Server acknowledged operation: " 
                 << operationTypeToString(operationType) << endl;
        }
    }

    // Request creation helpers
    vector<byte> createQueryAvailabilityRequest(int requestId, const vector<string>& args) {
        if (args.size() < 2) {
            throw runtime_error("Usage: query <facility_name> <day1> <day2> ...");
        }
        
        string facilityName = args[0];
        vector<DayOfWeek> days;
        
        for (size_t i = 1; i < args.size(); i++) {
            days.push_back(stringToDayOfWeek(args[i]));
        }
        
        return Marshaller::marshalQueryAvailabilityRequest(requestId, facilityName, days);
    }

    vector<byte> createBookFacilityRequest(int requestId, const vector<string>& args) {
        if (args.size() != 5) {
            throw runtime_error("Usage: book <facility_name> <start_day> <start_time> <end_day> <end_time>");
        }
        
        string facilityName = args[0];
        DateTime startTime = parseDateTime(args[1] + " " + args[2]);
        DateTime endTime = parseDateTime(args[3] + " " + args[4]);
        
        return Marshaller::marshalBookFacilityRequest(requestId, facilityName, startTime, endTime);
    }

    vector<byte> createChangeBookingRequest(int requestId, const vector<string>& args) {
        if (args.size() != 2) {
            throw runtime_error("Usage: change <confirmation_id> <offset_minutes>");
        }
        
        string confirmationId = args[0];
        int offsetMinutes;
        
        try {
            offsetMinutes = stoi(args[1]);
        } catch (const exception& e) {
            throw runtime_error("Invalid offset minutes. Must be an integer.");
        }
        
        return Marshaller::marshalChangeBookingRequest(requestId, confirmationId, offsetMinutes);
    }

    vector<byte> createMonitorAvailabilityRequest(int requestId, const vector<string>& args) {
        if (args.size() != 2) {
            throw runtime_error("Usage: monitor <facility_name> <interval_minutes>");
        }
        
        string facilityName = args[0];
        int intervalMinutes;
        
        try {
            intervalMinutes = stoi(args[1]);
        } catch (const exception& e) {
            throw runtime_error("Invalid monitor interval minutes. Must be an integer.");
        }
        
        return Marshaller::marshalMonitorAvailabilityRequest(requestId, facilityName, intervalMinutes);
    }

    vector<byte> createExtendBookingRequest(int requestId, const vector<string>& args) {
        if (args.size() != 2) {
            throw runtime_error("Usage: extend <confirmation_id> <extend_minutes>");
        }
        
        string confirmationId = args[0];
        int extendMinutes;
        
        try {
            extendMinutes = stoi(args[1]);
        } catch (const exception& e) {
            throw runtime_error("Invalid extend minutes. Must be an integer.");
        }
        
        return Marshaller::marshalExtendBookingRequest(requestId, confirmationId, extendMinutes);
    }

    // Helper methods
    DateTime parseDateTime(const string& dateTimeStr) {
        // Parse string like "MONDAY 09:00"
        size_t spacePos = dateTimeStr.find(' ');
        if (spacePos == string::npos) {
            throw runtime_error("Invalid date time format");
        }

        string dayStr = dateTimeStr.substr(0, spacePos);
        string timeStr = dateTimeStr.substr(spacePos + 1);

        DayOfWeek day = stringToDayOfWeek(dayStr);

        size_t colonPos = timeStr.find(':');
        if (colonPos == string::npos) {
            throw runtime_error("Invalid time format");
        }

        int hour = stoi(timeStr.substr(0, colonPos));
        int minute = stoi(timeStr.substr(colonPos + 1));

        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw runtime_error("Invalid time values");
        }

        DateTime dt;
        dt.dayOfWeek = day;
        dt.hour = hour;
        dt.minute = minute;
        return dt;
    }

    DayOfWeek stringToDayOfWeek(const string& day) {
        string upperDay;
        upperDay.resize(day.size());
        transform(day.begin(), day.end(), upperDay.begin(), ::toupper);

        if (upperDay == "MONDAY") return MONDAY;
        if (upperDay == "TUESDAY") return TUESDAY;
        if (upperDay == "WEDNESDAY") return WEDNESDAY;
        if (upperDay == "THURSDAY") return THURSDAY;
        if (upperDay == "FRIDAY") return FRIDAY;
        if (upperDay == "SATURDAY") return SATURDAY;
        if (upperDay == "SUNDAY") return SUNDAY;
        
        throw runtime_error("Invalid day: " + day);
    }

    string operationTypeToString(OperationType type) {
        switch (type) {
            case QUERY_AVAILABILITY: return "QUERY_AVAILABILITY";
            case BOOK_FACILITY: return "BOOK_FACILITY";
            case CHANGE_BOOKING: return "CHANGE_BOOKING";
            case MONITOR_AVAILABILITY: return "MONITOR_AVAILABILITY";
            case GET_SERVER_STATUS: return "GET_SERVER_STATUS";
            case EXTEND_BOOKING: return "EXTEND_BOOKING";
            default: return "UNKNOWN";
        }
    }

public:
    BookingClient(const string& serverAddressStr, int serverPort) 
        : serverAddress(serverAddressStr), serverPort(serverPort) {
        
        // Initialize random seed
        srand(time(nullptr));
        
        // Create UDP socket
        clientSocket = socket(AF_INET, SOCK_DGRAM, 0);
        if (clientSocket < 0) {
            cerr << "Error creating socket" << endl;
            exit(1);
        }
        
        // Setup server address structure
        memset(&serverAddr, 0, sizeof(serverAddr));
        serverAddr.sin_family = AF_INET;
        serverAddr.sin_port = htons(serverPort);
        
        if (inet_pton(AF_INET, serverAddress.c_str(), &serverAddr.sin_addr) <= 0) {
            cerr << "Error: Invalid address " << serverAddress << endl;
            close(clientSocket);
            exit(1);
        }
    }

    ~BookingClient() {
        if (clientSocket >= 0) {
            close(clientSocket);
        }
    }

    void setAtLeastOnceSemanticsEnabled(bool enabled) {
        atLeastOnceSemanticsEnabled = enabled;
    }

    void setPacketLossProbability(double probability) {
        packetLossProbability = probability;
    }

    void start() {
        string input;
        vector<string> args;
        
        cout << "Booking Client started. Type 'exit' to quit." << endl;
        
        while (true) {
            cout << "Enter command (query, book, change, monitor, status, extend, exit): ";
            getline(cin, input);
            
            // Tokenize input
            stringstream ss(input);
            string token;
            vector<string> tokens;
            
            while (getline(ss, token, ' ')) {
                if (!token.empty()) {
                    tokens.push_back(token);
                }
            }
            
            if (tokens.empty()) continue;
            
            string command = tokens[0];
            
            if (command == "exit") {
                cout << "Exiting client." << endl;
                break;
            }
            
            // Extract arguments
            args.clear();
            for (size_t i = 1; i < tokens.size(); i++) {
                args.push_back(tokens[i]);
            }
            
            vector<byte> request = createRequest(command, args);
            if (!request.empty()) {
                vector<byte> responseData = sendRequest(request);
                if (!responseData.empty()) {
                    processResponse(responseData);
                }
            }
            
            // For monitor command, set up listening for updates if appropriate
            if (command == "monitor" && !args.empty()) {
                try {
                    int monitorInterval = stoi(args[1]);
                    cout << "Monitoring facility for " << monitorInterval << " minutes..." << endl;
                    
                    // Set a larger timeout for the monitoring period
                    struct timeval tv;
                    tv.tv_sec = monitorInterval * 60;
                    tv.tv_usec = 0;
                    if (setsockopt(clientSocket, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) < 0) {
                        cerr << "Failed to set socket timeout for monitoring" << endl;
                        continue;
                    }
                    
                    // Listen for updates until timeout
                    auto startTime = chrono::steady_clock::now();
                    auto endTime = startTime + chrono::minutes(monitorInterval);
                    
                    while (chrono::steady_clock::now() < endTime) {
                        vector<byte> buffer(MAX_MESSAGE_SIZE);
                        struct sockaddr_in from;
                        socklen_t fromlen = sizeof(from);
                        
                        int received = recvfrom(clientSocket, buffer.data(), buffer.size(), 0, 
                                             (struct sockaddr*)&from, &fromlen);
                        
                        if (received < 0) {
                            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                                // Timeout - monitoring period ended
                                break;
                            } else {
                                cerr << "Error receiving monitor update: " << strerror(errno) << endl;
                                break;
                            }
                        }
                        
                        // Process the monitor update
                        buffer.resize(received);
                        MessageHeader header = Marshaller::unmarshalHeader(buffer);
                        
                        if (header.operationType == MONITOR_AVAILABILITY && header.requestId == -1) {
                            // Extract facility name and availability data
                            vector<byte> payload(buffer.begin() + 7, buffer.begin() + 7 + header.payloadLength);
                            
                            // First 2 bytes are name length
                            uint16_t nameLength = (payload[0] << 8) | payload[1];
                            string facilityName(payload.begin() + 2, payload.begin() + 2 + nameLength);
                            string availabilityData(payload.begin() + 2 + nameLength, payload.end());
                            
                            cout << "\nReceived availability update for " << facilityName << ":\n"
                                 << availabilityData << endl;
                        }
                    }
                    
                    cout << "Monitoring period ended." << endl;
                    
                } catch (const exception& e) {
                    cerr << "Error during monitoring: " << e.what() << endl;
                }
            }
        }
    }
};

int main(int argc, char* argv[]) {
    string serverAddress = "127.0.0.1";
    int serverPort = SERVER_PORT;
    bool atLeastOnceSemanticsEnabled = false;
    double packetLossProbability = 0.0;
    
    // Parse command-line arguments
    for (int i = 1; i < argc; i++) {
        string arg = argv[i];
        
        if (arg == "-server" && i + 1 < argc) {
            serverAddress = argv[++i];
        } else if (arg == "-port" && i + 1 < argc) {
            try {
                serverPort = stoi(argv[++i]);
            } catch (const exception& e) {
                cerr << "Invalid port number. Using default port: " << serverPort << endl;
            }
        } else if (arg == "-semantics" && i + 1 < argc) {
            string semanticsArg = argv[++i];
            atLeastOnceSemanticsEnabled = (semanticsArg == "at-least-once");
        } else if (arg == "-loss" && i + 1 < argc) {
            try {
                packetLossProbability = stod(argv[++i]);
                if (packetLossProbability < 0 || packetLossProbability > 1) {
                    cerr << "Invalid packet loss probability. Using default: 0.0" << endl;
                    packetLossProbability = 0.0;
                }
            } catch (const exception& e) {
                cerr << "Invalid packet loss probability format. Using default: 0.0" << endl;
            }
        }
    }
    
    BookingClient client(serverAddress, serverPort);
    client.setAtLeastOnceSemanticsEnabled(atLeastOnceSemanticsEnabled);
    client.setPacketLossProbability(packetLossProbability);
    
    if (atLeastOnceSemanticsEnabled) {
        cout << "Client started with At-Least-Once semantics." << endl;
    } else {
        cout << "Client started with At-Most-Once semantics (default)." << endl;
    }
    
    if (packetLossProbability > 0) {
        cout << "Simulating packet loss with probability: " << packetLossProbability << endl;
    }
    
    client.start();
    
    return 0;
}