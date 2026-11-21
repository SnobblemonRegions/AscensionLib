package com.cobbleascend.store;

/** A store-level refusal with a stable code. Domain refusals (funds, ranks, ...) stay {@code CraftException}. */
public final class StoreException extends RuntimeException {
    public enum Code {
        /** REQUIRE_EXISTING was requested but no database file exists: stop mutating, never reset progression. */
        STORE_MISSING,
        STORE_CORRUPT,
        STORE_SCHEMA_UNKNOWN,
        /** The database belongs to a different world/progression authority. */
        AUTHORITY_MISMATCH,
        /** Stored content version differs from the active catalog; an explicit content migration is required. */
        CATALOG_MISMATCH,
        STORE_UNAVAILABLE,
        /** A persisted row failed validation; it is preserved, and mutations touching it are blocked. */
        STORED_DATA_INVALID,
        UNKNOWN_PROFILE,
        DUPLICATE_POKEMON,
        STALE_PROFILE,
        STALE_WALLET,
        /** An operation ID was reused with different parameters. */
        OPERATION_REUSED,
        /** A voucher batch the store never minted (a forged or foreign item). */
        UNKNOWN_VOUCHER,
        /** An unbond named a Pokemon that is not the host of a Fuser bond. */
        NOT_BONDED
    }

    private final Code code;

    public StoreException(Code code, String message) { super(message); this.code = code; }

    public StoreException(Code code, String message, Throwable cause) { super(message, cause); this.code = code; }

    public Code code() { return code; }
}
