# Distributed Facility Booking System

## 1. Objective

This project consolidates the basic knowledge of interprocess communication and remote invocation by requiring the design and implementation of a distributed facility booking system using UDP sockets.

## 2. Introduction

The system is built on a client–server architecture:

- **Server:**  
  Maintains information about facilities (e.g., meeting rooms, lecture theatres), including facility names, weekly availability (with time represented as day/hour/minute), and all bookings made by users.
  
- **Client:**  
  Provides a text-based interface for users to interact with the system by invoking services such as querying availability, booking, changing, extending, and monitoring facilities. The client sends requests to the server, which processes them and returns the results.

Communication is via UDP sockets. The server learns the client’s address on receipt of a request and replies (or sends callback updates) accordingly. For simplicity, client requests are assumed to be well separated in time (except for asynchronous monitor updates).

## 3. Experiment

### 3.1 Preliminaries

- **Communication Protocol:**  
  The system uses UDP sockets rather than TCP, so all message marshalling and unmarshalling is implemented manually.

- **Marshalling:**  
  Custom message formats are designed with a 8-byte header containing:
  - Request ID (4 bytes)
  - Operation type (1 byte)
  - Payload length (2 bytes)
  - Simulate Loss Byte (1 byte)
  The payload carries variable-length data (e.g., facility names), where each string is prefixed with its length.

- **Fault Tolerance:**  
  Fault tolerance is achieved by:
  - Implementing timeouts and retry mechanisms.
  - Filtering duplicate requests via a maintained history.
  - Caching replies to support at-least-once invocation semantics.
  - Simulating packet loss via providing additional arguments in user input.

### 3.2 System Description

The system implements the following services:

1. **Query Availability:**  
   Clients query the facility’s availability for one or more days. The server returns an availability schedule (using “O” for open and “X” for booked).

2. **Book Facility:**  
   Clients can book a facility by specifying the facility name, start time, and end time. On success, the server returns a unique confirmation ID.

3. **Change Booking:**  
   Clients can change a booking (shift its time by an offset in minutes) by providing the confirmation ID and offset. The system temporarily removes the booking for its availability check to avoid self-conflict.

4. **Monitor Availability:**  
   Clients may register to monitor a facility over a set time interval. During this period, any booking update triggers asynchronous callbacks to registered clients.

5. **Additional Operations:**  
   - **GET_SERVER_STATUS (Idempotent):** Returns server status (e.g., number of facilities and bookings).  
   - **EXTEND_BOOKING (Non-Idempotent):** Allows extension of an existing booking by a specified duration. For this operation, the booking is temporarily removed during the availability check.

### 3.3 Requirements and Design Decisions

- **Custom Message Formats:**  
  All messages are manually marshaled into byte arrays using `ByteBuffer` (Big Endian order).

- **Fault Tolerance:**  
  Timeouts, retries, duplicate filtering, and reply caching ensure the system operates reliably even in the presence of packet loss.

- **Invocation Semantics:**  
  Both At-Most-Once (AMO) and At-Least-Once (ALO) semantics are supported.  
  - AMO ignores duplicate requests.  
  - ALO detects duplicates and resends cached replies.

- **Simulated Packet Loss:**  
  Both client and server simulate packet loss via simulate loss byte in header to test fault tolerance.

## 4. Project Structure

```
|   compile.sh
|   run_client.sh
|   run_server.sh
|
+---client
|   |   BookingClient.cpp
|   |   Marshaller.cpp
|   |   Marshaller.h
|
+---server
|   |   Availability.java
|   |   Booking.java
|   |   BookingServer.java
|   |   Facility.java
|   |   FacilityService.java
|   |   MessageService.java
|   |   MonitorClient.java
|   |   RequestHandler.java
|   |   RequestHistory.java
|   |   TimeSlot.java
|   |
|   \---exceptions
|           FacilityBookingException.java
|
\---shared
    |   Marshaller.java
    |   MessageHeader.java
    |
    \---constants
            ArgumentConstants.java
            OperationType.java
            Protocol.h
            ProtocolConstants.java
```

## 5. Running the Project

### 5.1 Compilation

Use `compile.sh` to compile all Java files. A sample `compile.sh` might look like:

```bash
#!/bin/bash
mkdir -p bin
javac -d bin server/*.java shared/*.java shared/constants/*.java
```

### 5.2 Switching Between Semantics

Both the server and client scripts are modified to accept a semantics argument to switch between At-Most-Once and At-Least-Once invocation semantics.

#### run_server.sh

```bash
#!/bin/bash

# Default to at-most-once semantics if no argument is provided
semantics="at-most-once"

# Check if an argument is provided (semantics type)
if [ -n "$1" ]; then
  semantics="$1"
fi

echo "Starting Booking Server with semantics: $semantics"
java -cp bin server.BookingServer -semantics "$semantics"
```

#### run_client.sh

```bash
#!/bin/bash

# Default to at-most-once semantics if no argument is provided
semantics="at-most-once"

# Check if an argument is provided (semantics type)
if [ -n "$1" ]; then
  semantics="$1"
fi

echo "Starting Booking Client with semantics: $semantics"
java -cp bin client.BookingClient -semantics "$semantics"
```

**Usage Examples:**

- To run with At-Most-Once (default):

  ```bash
  ./run_server.sh
  ./run_client.sh
  ```

- To explicitly run with At-Most-Once:

  ```bash
  ./run_server.sh at-most-once
  ./run_client.sh at-most-once
  ```

- To run with At-Least-Once:

  ```bash
  ./run_server.sh at-least-once
  ./run_client.sh at-least-once
  ```

### 5.3 Running the Applications

After compiling, use the above scripts to start the server and client. For example, if testing with At-Least-Once semantics:

```bash
./run_server.sh at-least-once
./run_client.sh at-least-once
```

## 6. Usage

At the client prompt, enter commands as follows:

- **Query Availability:**  
  `query <facility_name> <day1> <day2> ...`  
  Example: `query Room101 monday`

- **Book Facility:**  
  `book <facility_name> <start_day> <start_time> <end_day> <end_time>`  
  Example: `book Room101 monday 09:00 monday 10:00`

- **Change Booking:**  
  `change <confirmation_id> <offset_minutes>`  
  Example: `change 43a785d9-03c9-42e4-a250-4b1453123c7c 60`

- **Extend Booking:**  
  `extend <confirmation_id> <extend_minutes>`  
  Example: `extend 43a785d9-03c9-42e4-a250-4b1453123c7c 60`

- **Monitor Availability:**  
  `monitor <facility_name> <monitor_interval_minutes>`  
  Example: `monitor Room101 5`

- **Get Server Status:**  
  `status`

- **Exit:**  
  `exit`

To simulate client packet loss, add the following to the end of any command:
- `-client-simulate-loss`

If using At-Least-Once, client will simulate packet loss on first attempt to send message.
If using At-Most-Once, client will not send the message.

To simulate server packet loss, add the following to the end of any command:
- `-server-simulate-loss`

If using At-Least-Once, server will not reply the first client request but it will cache the reply. On subsequent requests, server will retrieve reply from cache and send it to client. If cache misses, server will reprocess request.
If using At-Most-Once, server will not send any message.

## 7. Experimental Results & Observations

- **Invocation Semantics:**  
  Experiments show that At-Least-Once semantics may lead to duplicate processing if not handled properly, while At-Most-Once semantics prevent duplicate execution. The system’s fault-tolerance measures ensure robust operation under simulated packet loss conditions.

- **Monitoring:**  
  Multiple clients can monitor a facility concurrently, receiving asynchronous availability updates when bookings change.

## 8. Conclusion

This project implements a distributed facility booking system using UDP sockets and custom message formats. It demonstrates the use of manual marshalling/unmarshalling, fault-tolerance mechanisms, and configurable invocation semantics. 
