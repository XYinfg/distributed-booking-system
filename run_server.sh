#!/bin/bash

# Default to at-most-once semantics if no argument is provided
semantics="at-most-once"

# Check if an argument is provided (semantics type)
if [ -n "$1" ]; then
  semantics="$1"
fi

echo "Starting Booking Server with semantics: $semantics"
java server.BookingServer -semantics "$semantics"