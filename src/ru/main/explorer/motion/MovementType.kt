package ru.main.explorer.motion

/** ON_FOOT deliberately does not imply either walking or running. */
enum class MovementType {
    UNKNOWN,
    WALK,
    RUN,
    VEHICLE,
    ON_FOOT,
}
