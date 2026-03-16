package com.template.app.anova

enum class ConnectionMode {
    BLUETOOTH,   // BLE proximity only
    LOCAL_WIFI,  // Direct TCP on local network (same WiFi as device)
    CLOUD,       // Unofficial Anova cloud API — works from anywhere
    AUTO         // Try local WiFi first → fall back to cloud if unreachable
}
