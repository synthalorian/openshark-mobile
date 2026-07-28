package com.synthalorian.openshark.service

/**
 * JNI bindings to the embedded Rust gateway (libopenshark_gateway.so).
 *
 * The gateway serves the /v1 HTTP+SSE API on 127.0.0.1:9876 inside this
 * process — the app talks to it exactly like the old Termux-hosted server,
 * except it's compiled into the APK. No Termux required.
 */
object GatewayNative {
    init {
        System.loadLibrary("openshark_gateway")
    }

    /** Start the gateway. Returns the bound port (>0) or -1 on failure. */
    external fun nativeStart(configDir: String, port: Int): Int

    /** Stop the gateway. Returns 0 on success, -1 if not running. */
    external fun nativeStop(): Int

    /** JSON status: {"running":true,"port":9876} or {"running":false} */
    external fun nativeStatus(): String
}
