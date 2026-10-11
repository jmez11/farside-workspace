#pragma once
#include <stdint.h>
#include <time.h>
#include <poll.h>
// Socket reads may already have part of a field buffered. The library's general
// WaitForMessage then returns immediately, incorrectly exhausting retry-based
// timeouts. Poll the actual socket and measure elapsed monotonic time instead.
static uint64_t farsideReadClock(void) {
    struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint64_t)now.tv_sec * 1000000000ULL + now.tv_nsec;
}
static int farsideReadExpired(uint64_t start, unsigned seconds) {
    return farsideReadClock() - start >= (uint64_t)seconds * 1000000000ULL;
}
static void farsideWaitSocket(rfbClient* client, int microseconds) {
    struct pollfd descriptor = {client->sock, POLLIN, 0};
    poll(&descriptor, 1, microseconds / 1000);
}
