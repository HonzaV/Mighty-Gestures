package cz.mightybities.mightygestures.domain.time

/**
 * A source of monotonic nanosecond timestamps, injected everywhere domain code needs "now"
 * (AGENTS.md §4: never hard-code a clock so tests stay deterministic). The platform adapter wraps
 * `SystemClock.elapsedRealtimeNanos()`; tests use a fake that advances on command.
 *
 * A `fun interface` rather than a `() -> Long` typealias: a lambda parameter of function type
 * boxes on every invocation on some call shapes, a `fun interface` SAM does not (AC-M9 hot path).
 */
fun interface MonotonicClock {
    fun nanos(): Long
}
