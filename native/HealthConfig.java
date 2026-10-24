package xyz.lovestyle.home.canary;

/** Non-secret endpoint configuration only. */
public final class HealthConfig {
    public static final String INGEST_URL =
            "https://love-style.xyz/api/health/mobile/ingest";
    private HealthConfig() {}
}
