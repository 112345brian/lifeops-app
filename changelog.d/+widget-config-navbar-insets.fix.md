The widget configure screen's nav-bar-clearance padding now reads from live
window insets instead of a static platform resource, and no longer leaves a
permanent insets listener registered for the screen's whole lifetime.
