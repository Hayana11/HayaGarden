package xyz.lovestyle.home.canary;

/** CI replaces this source with a secret-free or configured build variant. */
public final class HealthConfig {
    public static final String INGEST_TOKEN = "";
    public static final String INGEST_URL =
            "https://love-style.xyz/api/health/mobile/ingest";
    private HealthConfig() {}
}
