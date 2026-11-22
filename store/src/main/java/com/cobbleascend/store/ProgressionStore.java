package com.cobbleascend.store;

import com.cobbleascend.domain.v1.*;
import com.cobbleascend.store.StoreException.Code;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Canonical progression store (specification sections 4.2, 6.1 and 13): profiles, material wallets and an
 * operation log in one embedded SQLite file. Every mutating call is one transaction that also records its
 * result under the caller's operation ID, so a repeated request returns the first result and a crash leaves
 * either nothing or the complete committed change. Ownership and battle/trade locks are checked by the
 * application service before calling; the store enforces revisions, funds and identity.
 *
 * <p>All access is serialized through this instance. Use one instance per world.
 */
public final class ProgressionStore implements AutoCloseable {
    public static final int SCHEMA_VERSION = 5;
    /** The most one withdrawal may mint: 36 stacks of 64. */
    public static final long MAX_MINT = 2304;

    public enum OpenMode { CREATE_IF_ABSENT, REQUIRE_EXISTING }

    public record Options(OpenMode mode, StoreConfig config, Clock clock, RandomGenerator random, Faults faults) {
        public Options {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(config);
            Objects.requireNonNull(clock);
            Objects.requireNonNull(random);
            Objects.requireNonNull(faults);
        }

        public static Options defaults() {
            return new Options(OpenMode.CREATE_IF_ABSENT, StoreConfig.defaults(), Clock.systemUTC(),
                    new SecureRandom(), Faults.NONE);
        }

        public Options withMode(OpenMode value) { return new Options(value, config, clock, random, faults); }
        public Options withConfig(StoreConfig value) { return new Options(mode, value, clock, random, faults); }
        public Options withClock(Clock value) { return new Options(mode, config, value, random, faults); }
        public Options withRandom(RandomGenerator value) { return new Options(mode, config, clock, value, faults); }
        public Options withFaults(Faults value) { return new Options(mode, config, clock, random, value); }
    }

    private interface Work<T> { T run() throws SQLException; }

    /** What a fresh operation produced, before it is recorded. */
    private record Fresh(Kind kind, UUID playerId, UUID pokemonId, ProfileV1 profile, MaterialWallet wallet,
                         Map<MaterialId, Long> cost, int creditsSpent, Map<MaterialId, Long> spent, Map<MaterialId, Long> gained, String detail) {
        Fresh(Kind kind, UUID playerId, UUID pokemonId, ProfileV1 profile, MaterialWallet wallet, Map<MaterialId, Long> cost, int creditsSpent) {
            this(kind, playerId, pokemonId, profile, wallet, cost, creditsSpent, Map.of(), Map.of(), "");
        }

        /** What the commit listener reports as moved; {@code cost} stays what the persisted result says, whatever it means for this kind. */
        Fresh moved(Map<MaterialId, Long> spent, Map<MaterialId, Long> gained, String detail) {
            return new Fresh(kind, playerId, pokemonId, profile, wallet, cost, creditsSpent, Map.copyOf(spent), Map.copyOf(gained), detail);
        }
    }

    private record Recorded(String hash, Kind kind, UUID pokemonId, String result) {}

    private final Path file;
    private final RankedRules rules;
    private final UUID authorityId;
    private final Options options;
    private final ProfileV1Codec codec;
    private final Connection db;
    private byte[] wildSecret;

    private ProgressionStore(Path file, RankedRules rules, UUID authorityId, Options options, Connection db) {
        this.file = file;
        this.rules = rules;
        this.authorityId = authorityId;
        this.options = options;
        this.codec = new ProfileV1Codec(rules);
        this.db = db;
    }

    public static ProgressionStore open(Path file, RankedRules rules, UUID authorityId) {
        return open(file, rules, authorityId, Options.defaults());
    }

    public static ProgressionStore open(Path file, RankedRules rules, UUID authorityId, Options options) {
        Objects.requireNonNull(rules);
        Objects.requireNonNull(authorityId);
        boolean existed = Files.exists(file);
        if (options.mode() == OpenMode.REQUIRE_EXISTING && !existed)
            throw new StoreException(Code.STORE_MISSING, "Progression store is missing: " + file);
        Connection connection = null;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            // Direct driver use: DriverManager's service lookup can miss a driver nested in a mod jar.
            connection = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
            }
            // An existing store about to be upgraded is copied first: a newer store cannot be opened by an older mod, so this copy is the way back.
            if (existed) backupBeforeUpgrade(connection, file, options);
            var store = new ProgressionStore(file, rules, authorityId, options, connection);
            store.initialize();
            return store;
        } catch (StoreException exception) {
            closeQuietly(connection);
            throw exception;
        } catch (SQLException | java.io.IOException exception) {
            closeQuietly(connection);
            throw new StoreException(Code.STORE_UNAVAILABLE, "Cannot open progression store", exception);
        }
    }

    /**
     * If this store's schema is older than {@link #SCHEMA_VERSION}, writes a consistent copy beside it before anything changes:
     * {@code progression.db.before-schema4-from3-20261015-120000.bak}. Fails closed: if the copy cannot be made the store is not upgraded.
     */
    private static void backupBeforeUpgrade(Connection connection, Path file, Options options) throws SQLException {
        String version = null;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT value FROM meta WHERE key = 'schema_version'")) {
            if (rows.next()) version = rows.getString(1);
        } catch (SQLException noMetaTable) {
            return;   // a file with no meta table is not one of ours yet; initialize() deals with it
        }
        if (version == null || version.equals(Integer.toString(SCHEMA_VERSION))) return;
        int old;
        try { old = Integer.parseInt(version); } catch (NumberFormatException unknown) { return; }   // an unknown format is refused by initialize()
        if (old > SCHEMA_VERSION) return;                                                              // a newer store is refused by initialize()
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC).format(options.clock().instant());
        Path backup = file.resolveSibling(file.getFileName() + ".before-schema" + SCHEMA_VERSION + "-from" + old + "-" + stamp + ".bak");
        try {
            snapshot(connection, backup);
        } catch (SQLException exception) {
            throw new StoreException(Code.STORE_UNAVAILABLE, "Could not back up the store before upgrading it (" + exception.getMessage() + "); it was left as it was", exception);
        }
    }

    /** SQLite writes a consistent copy of the whole database into a new file; the target must not exist. */
    private static void snapshot(Connection connection, Path target) throws SQLException {
        if (Files.exists(target)) throw new SQLException("Backup target already exists: " + target);
        try (var statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, target.toAbsolutePath().toString());
            statement.execute();
        }
    }

    /**
     * Writes a consistent copy of the live store to {@code target} (a new file), safe while the server runs: SQLite makes the copy itself, so it holds every
     * committed operation and none of an unfinished one. See {@link StoreBackups} for the dated snapshots the mod keeps.
     */
    public synchronized void backupTo(Path target) {
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            db.commit();
            boolean was = db.getAutoCommit();
            db.setAutoCommit(true);   // VACUUM cannot run inside a transaction
            try {
                snapshot(db, target);
            } finally {
                db.setAutoCommit(was);
            }
        } catch (SQLException | java.io.IOException exception) {
            throw new StoreException(Code.STORE_UNAVAILABLE, "Could not back up the progression store", exception);
        }
    }

    /** Reads the authority ID recorded in an existing store without opening it for writing. */
    public static Optional<UUID> peekAuthority(Path file) {
        if (!Files.exists(file)) return Optional.empty();
        var config = new org.sqlite.SQLiteConfig();
        config.setReadOnly(true);
        try (var connection = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), config.toProperties());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT value FROM meta WHERE key = 'authority_id'")) {
            return rows.next() ? Optional.of(UUID.fromString(rows.getString(1))) : Optional.empty();
        } catch (SQLException | IllegalArgumentException exception) {
            throw new StoreException(Code.STORE_UNAVAILABLE, "Cannot read the store's authority", exception);
        }
    }

    /** Confirms the bundled SQLite driver and its native library load in this runtime; returns the engine version. */
    public static String driverVersion() {
        try (var connection = new org.sqlite.JDBC().connect("jdbc:sqlite::memory:", new Properties());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT sqlite_version()")) {
            rows.next();
            return rows.getString(1);
        } catch (SQLException | LinkageError exception) {
            throw new StoreException(Code.STORE_UNAVAILABLE, "SQLite driver is not usable in this runtime", exception);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
    }

    private void initialize() throws SQLException {
        try (var statement = db.createStatement(); var check = statement.executeQuery("PRAGMA quick_check")) {
            if (!check.next() || !"ok".equals(check.getString(1)))
                throw new StoreException(Code.STORE_CORRUPT, "Progression store failed its integrity check");
        }
        db.setAutoCommit(false);
        try {
            try (var statement = db.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL) STRICT");
            }
            String version = meta("schema_version");
            if (version == null) {
                createSchema();
                putMeta("schema_version", Integer.toString(SCHEMA_VERSION));
                putMeta("authority_id", authorityId.toString());
                putMeta("catalog_version", Integer.toString(rules.catalogVersion()));
            } else {
                if (version.equals("1") || version.equals("2") || version.equals("3") || version.equals("4")) {
                    // Schema 2 added the fusions table, 3 the vouchers table, 4 the daily reward budget and 5 the hauntings: existing rows are untouched.
                    createFusionsTable();
                    createVouchersTable();
                    createBudgetTable();
                    createHauntingsTable();
                    try (var update = db.prepareStatement("UPDATE meta SET value = ? WHERE key = 'schema_version'")) {
                        update.setString(1, Integer.toString(SCHEMA_VERSION));
                        update.executeUpdate();
                    }
                } else if (!version.equals(Integer.toString(SCHEMA_VERSION)))
                    throw new StoreException(Code.STORE_SCHEMA_UNKNOWN, "Unsupported store schema " + version);
                if (!authorityId.toString().equals(meta("authority_id")))
                    throw new StoreException(Code.AUTHORITY_MISMATCH, "Store belongs to a different progression authority");
                if (!Integer.toString(rules.catalogVersion()).equals(meta("catalog_version")))
                    throw new StoreException(Code.CATALOG_MISMATCH, "Store content version requires an explicit migration");
            }
            // Balance phase 1's daily Threat counters: an extra table, created when missing, with no schema version bump, so an older build
            // still opens this store (it simply never reads the table) and a downgrade stays possible.
            createThreatTable();
            // Fuser bonds: likewise an extra table with no version bump. An older build ignores it and sees a bond as an ordinary fusion.
            createBondsTable();
            // Field Research: extra tables with no version bump, like the two above; an older build never reads them.
            createResearchTables();
            if (meta("wild_secret") == null) {
                byte[] secret = new byte[32];
                new SecureRandom().nextBytes(secret);
                putMeta("wild_secret", HexFormat.of().formatHex(secret));
            }
            wildSecret = HexFormat.of().parseHex(meta("wild_secret"));
            db.commit();
        } catch (RuntimeException | SQLException exception) {
            db.rollback();
            throw exception;
        }
    }

    private void createFusionsTable() throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS fusions(
                        host_pokemon_id TEXT PRIMARY KEY,
                        donor_pokemon_id TEXT NOT NULL UNIQUE,
                        host_species TEXT NOT NULL,
                        donor_species TEXT NOT NULL,
                        host_unique TEXT NOT NULL,
                        donor_unique TEXT NOT NULL,
                        operation_id TEXT NOT NULL,
                        fused_at INTEGER NOT NULL) STRICT""");
        }
    }

    private void createVouchersTable() throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS vouchers(
                        batch_id TEXT PRIMARY KEY,
                        player_id TEXT NOT NULL,
                        material_id TEXT NOT NULL,
                        minted INTEGER NOT NULL CHECK(minted >= 1),
                        outstanding INTEGER NOT NULL CHECK(outstanding >= 0),
                        delivered INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL) STRICT""");
            statement.execute("CREATE INDEX IF NOT EXISTS vouchers_undelivered ON vouchers(player_id, delivered)");
        }
    }

    private void createBudgetTable() throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS daily_budget(
                        player_id TEXT NOT NULL,
                        day INTEGER NOT NULL,
                        used INTEGER NOT NULL CHECK(used >= 0),
                        PRIMARY KEY(player_id, day)) STRICT""");
        }
    }

    private void createThreatTable() throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS threat_daily(
                        player_id TEXT NOT NULL,
                        day INTEGER NOT NULL,
                        counter TEXT NOT NULL,
                        used INTEGER NOT NULL CHECK(used >= 0),
                        PRIMARY KEY(player_id, day, counter)) STRICT""");
        }
    }

    private void createBondsTable() throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS bonds(
                        host_pokemon_id TEXT PRIMARY KEY,
                        donor_pokemon_id TEXT NOT NULL UNIQUE,
                        player_id TEXT NOT NULL,
                        fuse_operation_id TEXT NOT NULL,
                        price TEXT NOT NULL,
                        bonded_at INTEGER NOT NULL) STRICT""");
        }
    }

    private void createHauntingsTable() throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS hauntings(
                        pokemon_id TEXT PRIMARY KEY,
                        haunting_id TEXT NOT NULL,
                        rolls INTEGER NOT NULL CHECK(rolls >= 1),
                        operation_id TEXT NOT NULL,
                        updated_at INTEGER NOT NULL) STRICT""");
        }
    }

    private void createSchema() throws SQLException {
        createFusionsTable();
        createVouchersTable();
        createBudgetTable();
        createHauntingsTable();
        try (var statement = db.createStatement()) {
            statement.execute("""
                    CREATE TABLE profiles(
                        profile_id TEXT PRIMARY KEY,
                        pokemon_id TEXT NOT NULL UNIQUE,
                        revision INTEGER NOT NULL CHECK(revision >= 1),
                        projected_revision INTEGER NOT NULL DEFAULT 0,
                        body TEXT NOT NULL) STRICT""");
            statement.execute("""
                    CREATE TABLE wallets(
                        player_id TEXT PRIMARY KEY,
                        revision INTEGER NOT NULL CHECK(revision >= 1)) STRICT""");
            statement.execute("""
                    CREATE TABLE wallet_balances(
                        player_id TEXT NOT NULL REFERENCES wallets(player_id),
                        material_id TEXT NOT NULL,
                        amount INTEGER NOT NULL CHECK(amount >= 0),
                        PRIMARY KEY(player_id, material_id)) STRICT""");
            statement.execute("""
                    CREATE TABLE operations(
                        operation_id TEXT PRIMARY KEY,
                        request_hash TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        pokemon_id TEXT,
                        committed_at INTEGER NOT NULL,
                        result TEXT NOT NULL) STRICT""");
            statement.execute("""
                    CREATE TABLE milestone_awards(
                        profile_id TEXT NOT NULL REFERENCES profiles(profile_id),
                        milestone_level INTEGER NOT NULL CHECK(milestone_level BETWEEN 10 AND 100),
                        operation_id TEXT NOT NULL,
                        PRIMARY KEY(profile_id, milestone_level)) STRICT""");
            statement.execute("""
                    CREATE TABLE migrations(
                        profile_id TEXT PRIMARY KEY REFERENCES profiles(profile_id),
                        source_digest TEXT NOT NULL,
                        operation_id TEXT NOT NULL,
                        migrated_at INTEGER NOT NULL) STRICT""");
        }
    }

    private String meta(String key) throws SQLException {
        try (var statement = db.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    private void putMeta(String key, String value) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO meta(key, value) VALUES(?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    public Path file() { return file; }
    public UUID authorityId() { return authorityId; }

    /**
     * Server-only key that makes wild Pokemon ratings unpredictable yet fixed per Pokemon. Never send it to a
     * client or log it. Losing the store loses it, which is why store and world are backed up together.
     */
    public byte[] wildSecret() { return wildSecret.clone(); }

    @Override public synchronized void close() {
        try { db.close(); } catch (SQLException exception) {
            throw new StoreException(Code.STORE_UNAVAILABLE, "Cannot close progression store", exception);
        }
    }

    // --- reads ----------------------------------------------------------------------------------------

    public synchronized Optional<ProfileV1> profile(UUID pokemonId) {
        return read(() -> loadProfile(pokemonId));
    }

    public synchronized MaterialWallet wallet(UUID playerId) {
        return read(() -> loadWallet(playerId));
    }

    public synchronized boolean hasOperation(UUID operationId) {
        return read(() -> lookupOperation(operationId) != null);
    }

    public synchronized Set<Integer> awardedMilestones(UUID profileId) {
        return read(() -> {
            var levels = new TreeSet<Integer>();
            try (var statement = db.prepareStatement("SELECT milestone_level FROM milestone_awards WHERE profile_id = ?")) {
                statement.setString(1, profileId.toString());
                try (var rows = statement.executeQuery()) { while (rows.next()) levels.add(rows.getInt(1)); }
            }
            return levels;
        });
    }

    /** Profiles committed at a revision the Cobblemon projection has not yet received (crash recovery). */
    public synchronized List<ProfileV1> projectionBacklog() {
        return read(() -> {
            var result = new ArrayList<ProfileV1>();
            try (var statement = db.prepareStatement(
                    "SELECT pokemon_id, body FROM profiles WHERE revision > projected_revision ORDER BY pokemon_id");
                 var rows = statement.executeQuery()) {
                while (rows.next()) result.add(decodeProfile(rows.getString(2), UUID.fromString(rows.getString(1))));
            }
            return result;
        });
    }

    public synchronized void markProjected(UUID profileId, long revision) {
        transaction(() -> {
            try (var statement = db.prepareStatement("""
                    UPDATE profiles SET projected_revision = ?
                    WHERE profile_id = ? AND ? <= revision AND ? > projected_revision""")) {
                statement.setLong(1, revision);
                statement.setString(2, profileId.toString());
                statement.setLong(3, revision);
                statement.setLong(4, revision);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Runs a read and ends its implicit transaction so no snapshot stays open between calls. */
    private <T> T read(Work<T> work) {
        try {
            T result = work.run();
            db.commit();
            return result;
        } catch (RuntimeException exception) {
            rollbackQuietly();
            throw exception;
        } catch (SQLException exception) {
            rollbackQuietly();
            throw new StoreException(Code.STORE_UNAVAILABLE, "Progression store read failed", exception);
        }
    }

    // --- operations -----------------------------------------------------------------------------------

    /**
     * Registers a legitimately acquired Pokemon. The generator runs only when this operation has not already
     * committed, so a repeated capture callback resolves the existing profile instead of re-rolling it.
     */
    public synchronized Outcome acquire(UUID operationId, UUID pokemonId, Supplier<ProfileV1> generator) {
        return run(operationId, "ACQUIRE|" + pokemonId, () -> {
            if (loadProfile(pokemonId).isPresent())
                throw new StoreException(Code.DUPLICATE_POKEMON, "Pokemon already has a canonical profile");
            var profile = generator.get();
            if (!profile.pokemonId().equals(pokemonId))
                throw new IllegalArgumentException("Generated profile belongs to a different Pokemon");
            codec.encode(profile);
            insertProfile(profile);
            recordMilestones(profile.profileId(), profile.awardedMilestones(), operationId);
            return new Fresh(Kind.ACQUIRE, null, pokemonId, profile, null, Map.of(), 0);
        });
    }

    /**
     * Imports a stored schema-zero (or schema-1) profile once. Running it again for a Pokemon that already
     * migrated from the same source is a no-op that returns the existing profile.
     */
    public synchronized Outcome migrate(UUID operationId, UUID pokemonId, String encodedSource, int observedLevel) {
        String digest = sha256(encodedSource);
        return run(operationId, "MIGRATE|" + pokemonId + "|" + observedLevel + "|" + digest, () -> {
            var existing = loadProfile(pokemonId);
            if (existing.isPresent()) {
                if (!digest.equals(migrationDigest(existing.get().profileId())))
                    throw new StoreException(Code.DUPLICATE_POKEMON, "Pokemon already has a different canonical profile");
                return new Fresh(Kind.MIGRATE, null, pokemonId, existing.get(), null, Map.of(), 0);
            }
            var profile = new SchemaZeroMigration(rules).importStored(encodedSource, pokemonId, authorityId, observedLevel);
            insertProfile(profile);
            recordMilestones(profile.profileId(), profile.awardedMilestones(), operationId);
            try (var statement = db.prepareStatement(
                    "INSERT INTO migrations(profile_id, source_digest, operation_id, migrated_at) VALUES(?, ?, ?, ?)")) {
                statement.setString(1, profile.profileId().toString());
                statement.setString(2, digest);
                statement.setString(3, operationId.toString());
                statement.setLong(4, options.clock().millis());
                statement.executeUpdate();
            }
            return new Fresh(Kind.MIGRATE, null, pokemonId, profile, null, Map.of(), 0);
        });
    }

    /**
     * Spends one Ascension Sigil and registers the profile the generator makes, in one transaction: either both happen or neither.
     * Refuses a Pokemon that already has a profile (the Sigil is not spent) and a wallet with no Sigil. Idempotent per operation ID.
     */
    public synchronized Outcome useSigil(UUID operationId, UUID playerId, UUID pokemonId, Supplier<ProfileV1> generator) {
        return run(operationId, "SIGIL|" + playerId + "|" + pokemonId, () -> {
            if (loadProfile(pokemonId).isPresent())
                throw new StoreException(Code.DUPLICATE_POKEMON, "Pokemon already has a canonical profile");
            var before = loadWallet(playerId);
            var cost = Map.of(MaterialId.ASCENSION_SIGIL, 1L);
            var after = before.debit(cost);
            var profile = generator.get();
            if (!profile.pokemonId().equals(pokemonId))
                throw new IllegalArgumentException("Generated profile belongs to a different Pokemon");
            codec.encode(profile);
            options.faults().at(Faults.Point.AFTER_VALIDATION);
            insertProfile(profile);
            recordMilestones(profile.profileId(), profile.awardedMilestones(), operationId);
            saveWallet(playerId, before, after);
            return new Fresh(Kind.USE_SIGIL, playerId, pokemonId, profile, after, cost, 0).moved(cost, Map.of(), "sigil");
        });
    }

    /** Credits materials (rewards, admin grants). Idempotent per operation ID. */
    public synchronized Outcome grant(UUID operationId, UUID playerId, Map<MaterialId, Long> materials, String reason) {
        Objects.requireNonNull(reason);
        var canonical = new TreeMap<String, Long>();
        materials.forEach((id, amount) -> canonical.put(id.id(), amount));
        return run(operationId, "GRANT|" + playerId + "|" + canonical + "|" + reason, () -> {
            var before = loadWallet(playerId);
            var after = before.credit(materials);
            if (after == before) throw new CraftException(CraftException.Reason.INVALID_AMOUNT, "Nothing to grant");
            saveWallet(playerId, before, after);
            return new Fresh(Kind.GRANT, playerId, null, null, after, Map.of(), 0).moved(Map.of(), materials, reason);
        });
    }

    /** The UTC day number the daily budget counts in. */
    private long budgetDay() { return Math.floorDiv(options.clock().millis(), 86_400_000L); }

    /** How many budgeted chests this player has been paid today (UTC). */
    public synchronized int budgetUsedToday(UUID playerId) {
        return read(() -> {
            try (var statement = db.prepareStatement("SELECT used FROM daily_budget WHERE player_id = ? AND day = ?")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, budgetDay());
                try (var rows = statement.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
            }
        });
    }

    /** How many times a player has used a daily Threat counter today (UTC): {@code core_roll} for tower Core rolls, {@code apex} for raid Apex pay. */
    public synchronized int threatUsedToday(UUID playerId, String counter) {
        Objects.requireNonNull(counter);
        return read(() -> {
            try (var statement = db.prepareStatement("SELECT used FROM threat_daily WHERE player_id = ? AND day = ? AND counter = ?")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, budgetDay());
                statement.setString(3, counter);
                try (var rows = statement.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
            }
        });
    }

    /**
     * {@link #grantBudgeted} that also counts one use of a daily Threat counter in the same transaction (balance phase 1). The caller decides the amounts
     * from {@link #threatUsedToday} before calling; a retry replays the first payment and counts nothing more.
     */
    public synchronized Outcome grantBudgeted(UUID operationId, UUID playerId, Map<MaterialId, Long> materials, String reason,
                                              com.cobbleascend.domain.v1.DailyRewardBudget budget, String threatCounter) {
        if (threatCounter == null) return grantBudgeted(operationId, playerId, materials, reason, budget);
        Objects.requireNonNull(reason);
        var canonical = new TreeMap<String, Long>();
        materials.forEach((id, amount) -> canonical.put(id.id(), amount));
        return run(operationId, "GRANT_BUDGETED|" + playerId + "|" + canonical + "|" + reason + "|" + threatCounter, () -> {
            var outcome = budgetedGrant(playerId, materials, reason, budget);
            long day = budgetDay();
            try (var statement = db.prepareStatement("""
                    INSERT INTO threat_daily(player_id, day, counter, used) VALUES(?, ?, ?, 1)
                    ON CONFLICT(player_id, day, counter) DO UPDATE SET used = used + 1""")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, day);
                statement.setString(3, threatCounter);
                statement.executeUpdate();
            }
            try (var statement = db.prepareStatement("DELETE FROM threat_daily WHERE day < ?")) {
                statement.setLong(1, day - 7);
                statement.executeUpdate();
            }
            return outcome;
        });
    }

    /**
     * Credits a chest against the daily budget: once the player has been paid {@code budget.fullPerDay()} chests today, this one pays with its dust cut to
     * {@code budget.reducedDustPercent()} percent. The request is hashed with the amounts the caller asked for, so a retry (same operation ID, same amounts)
     * replays exactly what was paid, however the counter has moved since, and a replay never uses another unit of the budget. The wallet receives the
     * possibly reduced amounts, which come back in {@link Outcome#cost()}.
     */
    public synchronized Outcome grantBudgeted(UUID operationId, UUID playerId, Map<MaterialId, Long> materials, String reason,
                                              com.cobbleascend.domain.v1.DailyRewardBudget budget) {
        Objects.requireNonNull(reason);
        var canonical = new TreeMap<String, Long>();
        materials.forEach((id, amount) -> canonical.put(id.id(), amount));
        return run(operationId, "GRANT_BUDGETED|" + playerId + "|" + canonical + "|" + reason, () -> budgetedGrant(playerId, materials, reason, budget));
    }

    /** The body of a budgeted grant, run inside an operation by the two public forms. */
    private Fresh budgetedGrant(UUID playerId, Map<MaterialId, Long> materials, String reason,
                                com.cobbleascend.domain.v1.DailyRewardBudget budget) throws SQLException {
        {
            long day = budgetDay();
            int used = 0;
            try (var statement = db.prepareStatement("SELECT used FROM daily_budget WHERE player_id = ? AND day = ?")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, day);
                try (var rows = statement.executeQuery()) { if (rows.next()) used = rows.getInt(1); }
            }
            var paid = budget.reduces(used) ? budget.reduced(materials) : materials;
            var before = loadWallet(playerId);
            var after = before.credit(paid);
            if (after == before) throw new CraftException(CraftException.Reason.INVALID_AMOUNT, "Nothing to grant");
            saveWallet(playerId, before, after);
            try (var statement = db.prepareStatement("""
                    INSERT INTO daily_budget(player_id, day, used) VALUES(?, ?, 1)
                    ON CONFLICT(player_id, day) DO UPDATE SET used = used + 1""")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, day);
                statement.executeUpdate();
            }
            try (var statement = db.prepareStatement("DELETE FROM daily_budget WHERE day < ?")) {
                statement.setLong(1, day - 7);
                statement.executeUpdate();
            }
            return new Fresh(Kind.GRANT, playerId, null, null, after, Map.copyOf(paid), 0).moved(Map.of(), paid, paid == materials ? reason : reason + " (reduced)");
        }
    }

    /** Debits materials for a non-craft use (e.g. a Scouter). Idempotent per operation ID; refuses overdrafts. */
    public synchronized Outcome spend(UUID operationId, UUID playerId, Map<MaterialId, Long> materials, String reason) {
        Objects.requireNonNull(reason);
        var canonical = new TreeMap<String, Long>();
        materials.forEach((id, amount) -> canonical.put(id.id(), amount));
        return run(operationId, "SPEND|" + playerId + "|" + canonical + "|" + reason, () -> {
            var before = loadWallet(playerId);
            var after = before.debit(materials);
            if (after == before) throw new CraftException(CraftException.Reason.INVALID_AMOUNT, "Nothing to spend");
            options.faults().at(Faults.Point.AFTER_VALIDATION);
            saveWallet(playerId, before, after);
            return new Fresh(Kind.SPEND, playerId, null, null, after, materials, 0).moved(materials, Map.of(), reason);
        });
    }

    /** Consumes the configured fragment threshold and credits one Catalyst. */
    public synchronized Outcome assembleCatalyst(UUID operationId, UUID playerId, long expectedWalletRevision) {
        return run(operationId, "ASSEMBLE|" + playerId + "|" + expectedWalletRevision, () -> {
            var before = loadWallet(playerId);
            if (before.revision() != expectedWalletRevision)
                throw new StoreException(Code.STALE_WALLET, "Wallet changed since it was shown");
            var after = before.assembleCatalyst(options.config().catalystFragments());
            options.faults().at(Faults.Point.AFTER_VALIDATION);
            saveWallet(playerId, before, after);
            var fragments = Map.of(MaterialId.UNIQUE_FRAGMENT, (long) options.config().catalystFragments());
            return new Fresh(Kind.ASSEMBLE_CATALYST, playerId, null, null, after, fragments, 0).moved(fragments, Map.of(MaterialId.UNIQUE_CATALYST, 1L), "assemble");
        });
    }

    /**
     * Trades one bundle of materials for another in one transaction (the Tower Marks route to an Hourglass of Undoing): the debit and the credit commit
     * together, a short wallet changes nothing, and the operation ID makes a retry a replay. The wallet revision the caller saw must still hold.
     */
    public synchronized Outcome exchange(UUID operationId, UUID playerId, Map<MaterialId, Long> pay, Map<MaterialId, Long> receive, String reason, long expectedWalletRevision) {
        Objects.requireNonNull(reason);
        var payText = new TreeMap<String, Long>();
        pay.forEach((id, amount) -> payText.put(id.id(), amount));
        var getText = new TreeMap<String, Long>();
        receive.forEach((id, amount) -> getText.put(id.id(), amount));
        return run(operationId, "EXCHANGE|" + playerId + "|" + payText + "|" + getText + "|" + reason + "|" + expectedWalletRevision, () -> {
            var before = loadWallet(playerId);
            if (expectedWalletRevision != CraftRequest.ANY_REVISION && before.revision() != expectedWalletRevision)
                throw new StoreException(Code.STALE_WALLET, "Wallet changed since it was shown");
            var after = before.debit(pay).credit(receive);
            if (after == before) throw new CraftException(CraftException.Reason.INVALID_AMOUNT, "Nothing to exchange");
            options.faults().at(Faults.Point.AFTER_VALIDATION);
            saveWallet(playerId, before, after);
            return new Fresh(Kind.EXCHANGE, playerId, null, null, after, pay, 0).moved(pay, receive, reason);
        });
    }

    // ---- hauntings: the Halloween 2026 event power, one per Pokemon ----------------------------------------------------

    /** The Haunting this Pokemon holds, if any. */
    public synchronized Optional<HauntingRecord> haunting(UUID pokemonId) {
        return read(() -> loadHaunting(pokemonId));
    }

    /** Every Pokemon that holds a Haunting, for the battle payload and the guide; one small query. */
    public synchronized Map<UUID, String> hauntedPokemon() {
        return read(() -> {
            var out = new HashMap<UUID, String>();
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT pokemon_id, haunting_id FROM hauntings")) {
                while (rows.next()) out.put(UUID.fromString(rows.getString(1)), rows.getString(2));
            }
            return out;
        });
    }

    private Optional<HauntingRecord> loadHaunting(UUID pokemonId) throws SQLException {
        try (var statement = db.prepareStatement("SELECT pokemon_id, haunting_id, rolls, operation_id, updated_at FROM hauntings WHERE pokemon_id = ?")) {
            statement.setString(1, pokemonId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new HauntingRecord(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getInt(3),
                        UUID.fromString(rows.getString(4)), rows.getLong(5)));
            }
        }
    }

    /**
     * Gives a Pokemon a random Haunting for Spooky Essence, or re-rolls the one it has (the new one replaces the old). In one transaction: the Pokemon
     * must have a profile, the wallet revision the caller saw must still hold, the price for this roll is debited, the Haunting is drawn from a seed
     * made of the operation ID and the Pokemon (so a retry lands on the same one) and recorded, and the profile revision advances so every cache of it
     * refreshes. The operation ID makes a retry a replay; read the result with {@link #haunting}.
     */
    public synchronized Outcome haunt(UUID operationId, UUID playerId, UUID pokemonId, long expectedWalletRevision) {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(pokemonId);
        return run(operationId, "HAUNT|" + playerId + "|" + pokemonId + "|" + expectedWalletRevision, () -> {
            var profile = loadProfile(pokemonId).orElseThrow(() -> new StoreException(Code.UNKNOWN_PROFILE, "No profile for the Pokemon"));
            var before = loadWallet(playerId);
            if (expectedWalletRevision != CraftRequest.ANY_REVISION && before.revision() != expectedWalletRevision)
                throw new StoreException(Code.STALE_WALLET, "Wallet changed since it was shown");
            int rolls = loadHaunting(pokemonId).map(HauntingRecord::rolls).orElse(0);
            var cost = Map.of(MaterialId.SPOOKY_ESSENCE, Hauntings.cost(rolls));
            var after = before.debit(cost);
            options.faults().at(Faults.Point.AFTER_VALIDATION);
            var drawn = Hauntings.roll(Hauntings.seed(operationId, pokemonId));
            try (var statement = db.prepareStatement("INSERT INTO hauntings(pokemon_id, haunting_id, rolls, operation_id, updated_at) VALUES(?, ?, ?, ?, ?)"
                    + " ON CONFLICT(pokemon_id) DO UPDATE SET haunting_id = excluded.haunting_id, rolls = excluded.rolls,"
                    + " operation_id = excluded.operation_id, updated_at = excluded.updated_at")) {
                statement.setString(1, pokemonId.toString());
                statement.setString(2, drawn.id());
                statement.setInt(3, rolls + 1);
                statement.setString(4, operationId.toString());
                statement.setLong(5, options.clock().millis());
                statement.executeUpdate();
            }
            var next = profile.bumped();
            updateProfile(next, profile.revision());
            saveWallet(playerId, before, after);
            return new Fresh(Kind.HAUNT, playerId, pokemonId, next, after, cost, 0).moved(cost, Map.of(), drawn.id());
        });
    }

    // ---- vouchers: wallet currency as tradable items -------------------------------------------------------

    /** The batch id a mint with this operation ID gets, so the caller (and a replay) knows it without asking the store. */
    public static UUID voucherBatchId(UUID operationId) {
        return UUID.nameUUIDFromBytes(("voucher|" + operationId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Withdraws {@code amount} of a material from the wallet into a new voucher batch, in one transaction (the wallet pays the amount plus the
     * {@link CurrencyItems#withdrawalFee fee}, the batch holds the amount; a bound material such as a Tower Mark is refused): the debit and the batch row commit
     * together. The batch is not yet {@code delivered}; the caller hands the items over and then calls {@link #markVoucherDelivered}.
     * Idempotent per operation ID. At most {@link #MAX_MINT} units.
     */
    public synchronized Outcome mintVoucher(UUID operationId, UUID playerId, MaterialId material, long amount, long expectedWalletRevision) {
        if (amount < 1 || amount > MAX_MINT) throw new CraftException(CraftException.Reason.INVALID_AMOUNT, "A withdrawal is 1 to " + MAX_MINT);
        if (!CurrencyItems.spec(material).tradable())
            throw new CraftException(CraftException.Reason.INVALID_AMOUNT, CurrencyItems.spec(material).name() + " is bound to you and cannot be withdrawn");
        return run(operationId, "MINT|" + playerId + "|" + material.id() + "|" + amount + "|" + expectedWalletRevision, () -> {
            var before = loadWallet(playerId);
            if (expectedWalletRevision != CraftRequest.ANY_REVISION && before.revision() != expectedWalletRevision)
                throw new StoreException(Code.STALE_WALLET, "Wallet changed since it was shown");
            var debit = Map.of(material, amount + CurrencyItems.withdrawalFee(amount));
            var after = before.debit(debit);
            options.faults().at(Faults.Point.AFTER_VALIDATION);
            saveWallet(playerId, before, after);
            try (var statement = db.prepareStatement(
                    "INSERT INTO vouchers(batch_id, player_id, material_id, minted, outstanding, delivered, created_at) VALUES(?, ?, ?, ?, ?, 0, ?)")) {
                statement.setString(1, voucherBatchId(operationId).toString());
                statement.setString(2, playerId.toString());
                statement.setString(3, material.id());
                statement.setLong(4, amount);
                statement.setLong(5, amount);
                statement.setLong(6, options.clock().millis());
                statement.executeUpdate();
            }
            return new Fresh(Kind.MINT_VOUCHER, playerId, null, null, after, Map.of(material, amount), 0).moved(debit, Map.of(), "voucher");
        });
    }

    /** The batch id the reward-voucher issue with this operation ID gives one material. */
    public static UUID rewardBatchId(UUID operationId, MaterialId material) {
        return UUID.nameUUIDFromBytes(("reward_voucher|" + operationId + "|" + material.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Physical rewards: moves a reward the wallet was just paid into voucher batches, so it reaches the player as items to read and deposit. One
     * transaction: the wallet is debited exactly these amounts (no withdrawal fee, it is not a withdrawal) and one undelivered batch per material is
     * opened; delivery then works like a withdrawal's ({@link #undeliveredVouchers}). Bound materials (Tower Marks) are skipped and stay in the wallet,
     * as do amounts over {@link #MAX_MINT}. Idempotent per operation ID. A crash before this commits leaves the reward in the wallet, never lost or doubled.
     *
     * @return the materials actually turned into vouchers
     */
    public synchronized Map<MaterialId, Long> issueRewardVouchers(UUID operationId, UUID playerId, Map<MaterialId, Long> paid) {
        var issued = new java.util.EnumMap<MaterialId, Long>(MaterialId.class);
        paid.forEach((material, amount) -> {
            if (amount != null && amount > 0 && amount <= MAX_MINT && CurrencyItems.spec(material).tradable()) issued.put(material, amount);
        });
        if (issued.isEmpty()) return Map.of();
        var canonical = new TreeMap<String, Long>();
        issued.forEach((id, amount) -> canonical.put(id.id(), amount));
        run(operationId, "REWARD_VOUCHERS|" + playerId + "|" + canonical, () -> {
            var before = loadWallet(playerId);
            var after = before.debit(issued);
            options.faults().at(Faults.Point.AFTER_VALIDATION);
            saveWallet(playerId, before, after);
            for (var entry : issued.entrySet()) {
                try (var statement = db.prepareStatement(
                        "INSERT INTO vouchers(batch_id, player_id, material_id, minted, outstanding, delivered, created_at) VALUES(?, ?, ?, ?, ?, 0, ?)")) {
                    statement.setString(1, rewardBatchId(operationId, entry.getKey()).toString());
                    statement.setString(2, playerId.toString());
                    statement.setString(3, entry.getKey().id());
                    statement.setLong(4, entry.getValue());
                    statement.setLong(5, entry.getValue());
                    statement.setLong(6, options.clock().millis());
                    statement.executeUpdate();
                }
            }
            return new Fresh(Kind.MINT_VOUCHER, playerId, null, null, after, Map.copyOf(issued), 0).moved(Map.copyOf(issued), Map.of(), "reward_voucher");
        });
        return Map.copyOf(issued);
    }

    /** Records that the items of a batch reached the player. Safe to repeat. */
    public synchronized void markVoucherDelivered(UUID batchId) {
        transaction(() -> {
            try (var statement = db.prepareStatement("UPDATE vouchers SET delivered = 1 WHERE batch_id = ?")) {
                statement.setString(1, batchId.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Batches minted for this player whose items were never confirmed delivered (a crash or a full inventory): hand them over again. */
    public synchronized List<Voucher> undeliveredVouchers(UUID playerId) {
        return read(() -> {
            var out = new ArrayList<Voucher>();
            try (var statement = db.prepareStatement(
                    "SELECT batch_id, player_id, material_id, minted, outstanding, delivered, created_at FROM vouchers WHERE player_id = ? AND delivered = 0 AND outstanding > 0")) {
                statement.setString(1, playerId.toString());
                try (var rows = statement.executeQuery()) { while (rows.next()) out.add(readVoucher(rows)); }
            }
            return out;
        });
    }

    public synchronized Optional<Voucher> voucher(UUID batchId) {
        return read(() -> {
            try (var statement = db.prepareStatement(
                    "SELECT batch_id, player_id, material_id, minted, outstanding, delivered, created_at FROM vouchers WHERE batch_id = ?")) {
                statement.setString(1, batchId.toString());
                try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(readVoucher(rows)) : Optional.<Voucher>empty(); }
            }
        });
    }

    private Voucher readVoucher(java.sql.ResultSet rows) throws SQLException {
        return new Voucher(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), MaterialId.fromId(rows.getString(3)),
                rows.getLong(4), rows.getLong(5), rows.getInt(6) == 1, rows.getLong(7));
    }

    /**
     * Credits the redeemer for the units of a batch they hold, never more than the batch still has outstanding: the rest of {@code amount} are
     * counterfeit or already redeemed and credit nothing. The voucher is a bearer instrument, so the redeemer need not be the player it was
     * minted for. The credited amount comes back in {@link Outcome#cost()} (empty when nothing was left). Idempotent per operation ID. An unknown
     * batch throws {@link Code#UNKNOWN_VOUCHER} and records nothing.
     */
    public synchronized Outcome redeemVoucher(UUID operationId, UUID redeemerId, UUID batchId, long amount) {
        if (amount < 1) throw new CraftException(CraftException.Reason.INVALID_AMOUNT, "Nothing to redeem");
        return run(operationId, "REDEEM|" + redeemerId + "|" + batchId + "|" + amount, () -> {
            Voucher batch;
            try (var statement = db.prepareStatement(
                    "SELECT batch_id, player_id, material_id, minted, outstanding, delivered, created_at FROM vouchers WHERE batch_id = ?")) {
                statement.setString(1, batchId.toString());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new StoreException(Code.UNKNOWN_VOUCHER, "No such voucher batch");
                    batch = readVoucher(rows);
                }
            }
            long credit = Math.min(amount, batch.outstanding());
            var before = loadWallet(redeemerId);
            if (credit < 1) return new Fresh(Kind.REDEEM_VOUCHER, redeemerId, null, null, before, Map.of(), 0);
            var after = before.credit(Map.of(batch.material(), credit));
            options.faults().at(Faults.Point.AFTER_VALIDATION);
            saveWallet(redeemerId, before, after);
            try (var statement = db.prepareStatement("UPDATE vouchers SET outstanding = outstanding - ? WHERE batch_id = ?")) {
                statement.setLong(1, credit);
                statement.setString(2, batchId.toString());
                statement.executeUpdate();
            }
            return new Fresh(Kind.REDEEM_VOUCHER, redeemerId, null, null, after, Map.of(batch.material(), credit), 0).moved(Map.of(), Map.of(batch.material(), credit), "voucher");
        });
    }

    /** Executes one confirmed craft: revision checks, domain transition, debit, writes and result record. */
    public synchronized Outcome craft(CraftRequest request) {
        return run(request.operationId(), request.canonical(), () -> {
            var profile = loadProfile(request.pokemonId())
                    .orElseThrow(() -> new StoreException(Code.UNKNOWN_PROFILE, "No profile for that Pokemon"));
            if (profile.revision() != request.expectedProfileRevision())
                throw new StoreException(Code.STALE_PROFILE, "Profile changed since it was shown");
            MaterialWallet before = null;
            if (request.playerId() != null) {
                before = loadWallet(request.playerId());
                if (request.expectedWalletRevision() != CraftRequest.ANY_REVISION
                        && before.revision() != request.expectedWalletRevision())
                    throw new StoreException(Code.STALE_WALLET, "Wallet changed since it was shown");
            }
            var progression = new RankedProgression(rules);
            var random = options.random();
            ProfileV1 next;
            Map<MaterialId, Long> cost = Map.of();
            int credits = 0;
            switch (request.kind()) {
                case OBSERVE_LEVEL -> next = progression.observeLevel(profile, request.level()).profile();
                default -> {
                    var result = switch (request.kind()) {
                        case UPGRADE -> progression.upgrade(profile, request.target(), random);
                        case REFORGE -> progression.reforge(profile, request.target(), request.types(), random);
                        case REFINE -> progression.refine(profile, request.target(), random);
                        case PERFECT -> progression.perfect(profile, request.target());
                        case OVERREACH -> progression.overreach(profile, request.target(), random);
                        case PROMOTE -> progression.promote(profile, request.types(), random);
                        case AWARD_ATTUNEMENT -> progression.awardAttunement(profile, request.level());
                        case INSTALL_UNIQUE -> progression.installUnique(profile, request.target(), request.operationId());
                        case REPLACE_UNIQUE -> progression.replaceUnique(profile, request.target(), request.operationId());
                        case RESPEC_RANKS -> progression.respecRanks(profile, random);
                        case RESPEC_UNIQUE -> progression.respecUnique(profile, request.target(), request.operationId());
                        default -> throw new IllegalStateException("Unreachable craft kind");
                    };
                    next = result.profile();
                    cost = result.cost();
                    credits = result.creditsSpent();
                }
            }
            MaterialWallet after = before == null ? null : before.debit(cost);
            options.faults().at(Faults.Point.AFTER_VALIDATION);
            if (next != profile) {
                updateProfile(next, profile.revision());
                var added = new TreeSet<>(next.awardedMilestones());
                added.removeAll(profile.awardedMilestones());
                recordMilestones(next.profileId(), added, request.operationId());
            }
            if (after != null && after != before) saveWallet(request.playerId(), before, after);
            return new Fresh(request.kind(), request.playerId(), request.pokemonId(), next, after, cost, credits).moved(cost, Map.of(), "");
        });
    }

    /** The fusion that made this Pokemon a Transcendent, if any. */
    public synchronized Optional<Fusion> fusion(UUID hostId) {
        return read(() -> loadFusion("host_pokemon_id", hostId));
    }

    /** The fusion that consumed this Pokemon as a donor, if any: the Pokemon must be gone, and the caller finishes removing it. */
    public synchronized Optional<Fusion> consumedBy(UUID donorId) {
        return read(() -> loadFusion("donor_pokemon_id", donorId));
    }

    /** Every Pokemon that is a Transcendent host or a consumed donor, in one read: a screen listing candidates asks this once, not once each. */
    public synchronized Set<UUID> fusedPokemon() {
        return read(() -> {
            var ids = new HashSet<UUID>();
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT host_pokemon_id, donor_pokemon_id FROM fusions")) {
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString(1)));
                    ids.add(UUID.fromString(rows.getString(2)));
                }
            }
            return ids;
        });
    }

    private Optional<Fusion> loadFusion(String column, UUID id) throws SQLException {
        try (var statement = db.prepareStatement("SELECT host_pokemon_id, donor_pokemon_id, host_species, donor_species, host_unique,"
                + " donor_unique, operation_id, fused_at FROM fusions WHERE " + column + " = ?")) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new Fusion(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6), UUID.fromString(rows.getString(7)), rows.getLong(8)));
            }
        }
    }

    /**
     * Executes one confirmed fusion in one transaction: both revisions and the wallet are checked, {@link FusionRules} decides, the
     * price is debited, the fusion is recorded and the host revision advances. The donor profile row is left in place (like a
     * released Pokemon); the application service removes the donor Pokemon after this commits, and a replay of the same operation
     * ID returns the first result so an interrupted removal can be finished.
     */
    public synchronized Outcome fuse(FuseRequest request) {
        return fuse(request, false);
    }

    /**
     * As {@link #fuse(FuseRequest)}, for a fusion made at a Project Fusion Fuser: the same rules, price and record, plus a {@link Bond}
     * row in the same transaction. The donor is not consumed: it lives on inside the host, so the caller removes nothing. The price is
     * recorded so {@link #unbond} can refund exactly what was paid if the Fuser never finishes.
     */
    public synchronized Outcome bond(FuseRequest request) {
        return fuse(request, true);
    }

    /** The Fuser bond this Pokemon is the host or the donor of, if any. */
    public synchronized Optional<Bond> bondOf(UUID pokemonId) {
        return read(() -> {
            try (var statement = db.prepareStatement("SELECT host_pokemon_id, donor_pokemon_id, player_id, fuse_operation_id, price, bonded_at"
                    + " FROM bonds WHERE host_pokemon_id = ? OR donor_pokemon_id = ?")) {
                statement.setString(1, pokemonId.toString());
                statement.setString(2, pokemonId.toString());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    return Optional.of(new Bond(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)),
                            UUID.fromString(rows.getString(3)), UUID.fromString(rows.getString(4)), decodePrice(rows.getString(5)), rows.getLong(6)));
                }
            }
        });
    }

    /**
     * Undoes a Fuser bond whose Fuser never finished (the server stopped mid-fusion, or the Fuser failed and handed both Pokemon back):
     * the fusion and bond rows are removed, the recorded price is credited back to the player who paid it, and the host revision
     * advances. Idempotent per operation ID; refused when {@code hostId} holds no bond.
     */
    public synchronized Outcome unbond(UUID operationId, UUID hostId) {
        return run(operationId, "UNBOND|" + hostId, () -> {
            Bond bond;
            try (var statement = db.prepareStatement("SELECT donor_pokemon_id, player_id, fuse_operation_id, price, bonded_at FROM bonds WHERE host_pokemon_id = ?")) {
                statement.setString(1, hostId.toString());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new StoreException(Code.NOT_BONDED, "That Pokemon holds no Fuser bond");
                    bond = new Bond(hostId, UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), UUID.fromString(rows.getString(3)),
                            decodePrice(rows.getString(4)), rows.getLong(5));
                }
            }
            var host = loadProfile(hostId).orElseThrow(() -> new StoreException(Code.UNKNOWN_PROFILE, "No profile for the host"));
            for (String table : new String[] {"bonds", "fusions"}) {
                try (var statement = db.prepareStatement("DELETE FROM " + table + " WHERE host_pokemon_id = ?")) {
                    statement.setString(1, hostId.toString());
                    statement.executeUpdate();
                }
            }
            var before = loadWallet(bond.playerId());
            var after = before.credit(bond.price());
            var next = host.bumped();
            updateProfile(next, host.revision());
            saveWallet(bond.playerId(), before, after);
            return new Fresh(Kind.UNBOND, bond.playerId(), hostId, next, after, bond.price(), 0).moved(Map.of(), bond.price(), "");
        });
    }

    /** {@code DUST=600,FACET=30}: the price a bond row keeps, in material order. Package-visible for the tests. */
    static String encodePrice(Map<MaterialId, Long> price) {
        var out = new StringBuilder();
        new java.util.TreeMap<>(price).forEach((material, amount) -> {
            if (amount == null || amount <= 0) return;
            out.append(out.length() == 0 ? "" : ",").append(material.name()).append('=').append(amount);
        });
        return out.toString();
    }

    static Map<MaterialId, Long> decodePrice(String text) {
        var out = new java.util.EnumMap<MaterialId, Long>(MaterialId.class);
        if (text == null || text.isBlank()) return out;
        for (String part : text.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) throw new StoreException(Code.STORED_DATA_INVALID, "Unreadable bond price: " + text);
            try {
                out.merge(MaterialId.valueOf(part.substring(0, eq)), Long.parseLong(part.substring(eq + 1)), Long::sum);
            } catch (IllegalArgumentException exception) {
                throw new StoreException(Code.STORED_DATA_INVALID, "Unreadable bond price: " + text, exception);
            }
        }
        return out;
    }

    private Outcome fuse(FuseRequest request, boolean bond) {
        return run(request.operationId(), (bond ? "BOND|" : "") + request.canonical(), () -> {
            var host = loadProfile(request.hostId())
                    .orElseThrow(() -> new StoreException(Code.UNKNOWN_PROFILE, "No profile for the host"));
            var donor = loadProfile(request.donorId())
                    .orElseThrow(() -> new StoreException(Code.UNKNOWN_PROFILE, "No profile for the donor"));
            if (host.revision() != request.expectedHostRevision() || donor.revision() != request.expectedDonorRevision())
                throw new StoreException(Code.STALE_PROFILE, "A profile changed since it was shown");
            var before = loadWallet(request.playerId());
            if (request.expectedWalletRevision() != CraftRequest.ANY_REVISION && before.revision() != request.expectedWalletRevision())
                throw new StoreException(Code.STALE_WALLET, "Wallet changed since it was shown");
            boolean hostFused = loadFusion("host_pokemon_id", request.hostId()).isPresent() || loadFusion("donor_pokemon_id", request.hostId()).isPresent();
            boolean donorFused = loadFusion("host_pokemon_id", request.donorId()).isPresent() || loadFusion("donor_pokemon_id", request.donorId()).isPresent();
            FusionRules.check(host, hostFused, donor, donorFused, before);
            var cost = FusionRules.cost();
            var after = before.debit(cost);
            options.faults().at(Faults.Point.AFTER_VALIDATION);
            try (var statement = db.prepareStatement("INSERT INTO fusions(host_pokemon_id, donor_pokemon_id, host_species, donor_species,"
                    + " host_unique, donor_unique, operation_id, fused_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, request.hostId().toString());
                statement.setString(2, request.donorId().toString());
                statement.setString(3, request.hostSpecies());
                statement.setString(4, request.donorSpecies());
                statement.setString(5, host.unique().uniqueId());
                statement.setString(6, donor.unique().uniqueId());
                statement.setString(7, request.operationId().toString());
                statement.setLong(8, options.clock().millis());
                statement.executeUpdate();
            }
            if (bond) {
                try (var statement = db.prepareStatement("INSERT INTO bonds(host_pokemon_id, donor_pokemon_id, player_id, fuse_operation_id, price, bonded_at)"
                        + " VALUES(?, ?, ?, ?, ?, ?)")) {
                    statement.setString(1, request.hostId().toString());
                    statement.setString(2, request.donorId().toString());
                    statement.setString(3, request.playerId().toString());
                    statement.setString(4, request.operationId().toString());
                    statement.setString(5, encodePrice(cost));
                    statement.setLong(6, options.clock().millis());
                    statement.executeUpdate();
                }
            }
            var next = host.bumped();
            updateProfile(next, host.revision());
            saveWallet(request.playerId(), before, after);
            return new Fresh(Kind.FUSE, request.playerId(), request.hostId(), next, after, cost, 0).moved(cost, Map.of(), "");
        });
    }

    // ---- Field Research (docs/FIELD-RESEARCH-DESIGN.md): research, Bench studies, bound items, serums ----------------------------------------
    //
    // Every change is one transaction under an operation ID, so a retried or doubled call replays. Refusals throw ResearchException before
    // anything is written: nothing is recorded and the same request can be made again. The domain classes hold the rules; this section only
    // loads the facts from the database (never from the caller where the database knows them) and writes the result.

    private void createResearchTables() throws SQLException {
        try (var statement = db.createStatement()) {
            // Points and boss research per (player, species); the family meter is always recomputed from these, capped by the current shares.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS research_awards(
                        player_id TEXT NOT NULL,
                        species TEXT NOT NULL,
                        points INTEGER NOT NULL DEFAULT 0 CHECK(points >= 0),
                        boss_percent INTEGER NOT NULL DEFAULT 0 CHECK(boss_percent BETWEEN 0 AND 100),
                        PRIMARY KEY(player_id, species)) STRICT""");
            // Per-player counters per UTC day or week (serum crafts, Beacons).
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS research_days(
                        player_id TEXT NOT NULL,
                        counter TEXT NOT NULL,
                        period INTEGER NOT NULL,
                        used INTEGER NOT NULL CHECK(used >= 0),
                        PRIMARY KEY(player_id, counter, period)) STRICT""");
            // Bound items: Boss Samples (per species) and Data Drives (species "").
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS research_items(
                        player_id TEXT NOT NULL,
                        item TEXT NOT NULL,
                        species TEXT NOT NULL,
                        count INTEGER NOT NULL CHECK(count >= 0),
                        PRIMARY KEY(player_id, item, species)) STRICT""");
            // One running study per player and per Pokemon: the unique keys are the hard guarantee behind the friendly checks.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS research_jobs(
                        job_id TEXT PRIMARY KEY,
                        player_id TEXT NOT NULL UNIQUE,
                        pokemon_id TEXT NOT NULL UNIQUE,
                        species TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        started_at INTEGER NOT NULL,
                        ends_at INTEGER NOT NULL CHECK(ends_at > started_at)) STRICT""");
            // One armed serum per (player, family).
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS research_armed(
                        player_id TEXT NOT NULL,
                        family_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        stats TEXT NOT NULL,
                        arm_operation TEXT NOT NULL,
                        armed_at INTEGER NOT NULL,
                        PRIMARY KEY(player_id, family_id)) STRICT""");
            // What a serum did to a caught Pokemon: the IVs to project. A crash between this commit and the IV write is repaired from here
            // (the write only raises values, so applying it twice is harmless); the Pokemon's ID as the key means one serum per capture.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS research_serum_uses(
                        pokemon_id TEXT PRIMARY KEY,
                        player_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        ivs TEXT NOT NULL,
                        raised TEXT NOT NULL,
                        projected INTEGER NOT NULL DEFAULT 0,
                        used_at INTEGER NOT NULL) STRICT""");
            statement.execute("CREATE INDEX IF NOT EXISTS research_serum_uses_pending ON research_serum_uses(projected)");
        }
    }

    /** One player's research. */
    public synchronized ResearchLedger researchLedger(UUID playerId) {
        return read(() -> loadResearch(playerId));
    }

    /** The bound items of one kind a player holds, by species ("" for a Data Drive); only positive counts. */
    public synchronized Map<String, Integer> researchItems(UUID playerId, FieldResearch.Item item) {
        return read(() -> loadItems(playerId, item));
    }

    /** The player's running Bench study, if any. */
    public synchronized Optional<BenchStudies.Study> researchStudy(UUID playerId) {
        return read(() -> loadStudy("player_id", playerId));
    }

    /** The study a Pokemon is in, if any: the runtime refuses battle, trade, release, fusion and listings while one exists. */
    public synchronized Optional<BenchStudies.Study> researchStudyOfPokemon(UUID pokemonId) {
        return read(() -> loadStudy("pokemon_id", pokemonId));
    }

    /** Every Pokemon in a study, for the runtime's locks (one small query). */
    public synchronized Set<UUID> pokemonInResearch() {
        return read(() -> {
            var out = new HashSet<UUID>();
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT pokemon_id FROM research_jobs")) {
                while (rows.next()) out.add(UUID.fromString(rows.getString(1)));
            }
            return out;
        });
    }

    /** The serums a player has armed, by family. */
    public synchronized Map<String, Serums.Armed> armedSerums(UUID playerId) {
        return read(() -> {
            var out = new TreeMap<String, Serums.Armed>();
            try (var statement = db.prepareStatement("SELECT family_id, kind, stats FROM research_armed WHERE player_id = ?")) {
                statement.setString(1, playerId.toString());
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) out.put(rows.getString(1), new Serums.Armed(Serums.Kind.fromId(rows.getString(2)), rows.getString(1), stats(rows.getString(3))));
                }
            }
            return out;
        });
    }

    /** How many times a counter was used this period (a serum kind's crafts today or this week, Beacons today). */
    public synchronized int researchCounter(UUID playerId, String counter, long period) {
        return read(() -> counter(playerId, counter, period));
    }

    /**
     * Credits research to a species (a Laboratory locker, an admin grant; raids and the Bench have their own calls). A full share is not an
     * error: the operation is recorded and nothing changes, so a retry still replays. Unknown species or a bad amount: {@link ResearchException}.
     */
    public synchronized Outcome grantResearch(UUID operationId, UUID playerId, ResearchFamilies.Catalog catalog, String species, int amount, String reason) {
        String id = knownSpecies(catalog, species);
        if (amount < 1 || amount > FieldResearch.MAX_GRANT) throw new ResearchException(ResearchException.Reason.BAD_AMOUNT, Integer.toString(amount));
        Objects.requireNonNull(reason);
        return run(operationId, "RESEARCH_GRANT|" + playerId + "|" + id + "|" + amount + "|" + reason, () -> {
            creditResearch(playerId, catalog, id, amount);
            return new Fresh(Kind.RESEARCH, playerId, null, null, null, Map.of(), 0).moved(Map.of(), Map.of(), "research:" + id + "+" + amount + ":" + reason);
        });
    }

    /**
     * Pays one player for one won raid (decision 11): research for the boss's species by tier and one Boss Sample of it, together. Use the
     * operation {@code FieldResearch.operation("raid", <boss entity UUID>, player)}, so a doubled callback pays once.
     */
    public synchronized Outcome settleResearchRaid(UUID operationId, UUID playerId, ResearchFamilies.Catalog catalog, String bossSpecies,
                                                   FieldResearch.RaidTier tier) {
        String id = knownSpecies(catalog, bossSpecies);
        Objects.requireNonNull(tier);
        return run(operationId, "RESEARCH_RAID|" + playerId + "|" + id + "|" + tier, () -> {
            creditResearch(playerId, catalog, id, tier.research);
            addItem(playerId, FieldResearch.Item.BOSS_SAMPLE, id, 1);
            return new Fresh(Kind.RESEARCH, playerId, null, null, null, Map.of(), 0).moved(Map.of(), Map.of(), "raid:" + id + ":" + tier);
        });
    }

    /** Gives a player bound research items (a Laboratory's Data Drive; an admin grant). Count 1 to 64. */
    public synchronized Outcome grantResearchItem(UUID operationId, UUID playerId, ResearchFamilies.Catalog catalog, FieldResearch.Item item, String species, int count) {
        String id = item.perSpecies ? knownSpecies(catalog, species) : "";
        if (count < 1 || count > 64) throw new ResearchException(ResearchException.Reason.BAD_AMOUNT, Integer.toString(count));
        return run(operationId, "RESEARCH_ITEM|" + playerId + "|" + item.id + "|" + id + "|" + count, () -> {
            addItem(playerId, item, id, count);
            return new Fresh(Kind.RESEARCH, playerId, null, null, null, Map.of(), 0).moved(Map.of(), Map.of(), "item:" + item.id + ":" + id + "+" + count);
        });
    }

    /**
     * Uses one Data Drive on a species the player chooses: {@link FieldResearch.Source#LAB_DATA_DRIVE} research. Refused (the drive kept) when
     * the player has none or the species' share is already full, so a drive is never burnt for nothing.
     */
    public synchronized Outcome useDataDrive(UUID operationId, UUID playerId, ResearchFamilies.Catalog catalog, String species) {
        String id = knownSpecies(catalog, species);
        return run(operationId, "RESEARCH_DRIVE|" + playerId + "|" + id, () -> {
            if (loadResearch(playerId).roomFor(catalog, id) <= 0) throw new ResearchException(ResearchException.Reason.SHARE_FULL, id);
            takeItem(playerId, FieldResearch.Item.DATA_DRIVE, "", 1);
            creditResearch(playerId, catalog, id, FieldResearch.Source.LAB_DATA_DRIVE.points);
            return new Fresh(Kind.RESEARCH, playerId, null, null, null, Map.of(), 0).moved(Map.of(), Map.of(), "drive:" + id);
        });
    }

    /**
     * Starts a Bench study. The caller (the runtime) has checked that the player owns the Pokemon, that it is in their party or PC and not in a
     * battle, and passes its species and Laboratory tier mark; everything else (a running study, room left, boss research, samples) is read
     * here. An analysis consumes one Boss Sample of the species now (recall loses it, follow-up decision 3).
     */
    public synchronized Outcome startResearchStudy(UUID operationId, UUID playerId, ResearchFamilies.Catalog catalog, BenchStudies.Kind kind,
                                                   UUID pokemonId, String species, int zoneTier, UUID jobId) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(pokemonId);
        Objects.requireNonNull(jobId);
        String id = ResearchFamilies.normalise(species);
        return run(operationId, "RESEARCH_STUDY|" + playerId + "|" + kind + "|" + pokemonId + "|" + id + "|" + zoneTier + "|" + jobId, () -> {
            var ledger = loadResearch(playerId);
            var request = new BenchStudies.Request(kind, playerId, pokemonId, id, catalog.knows(id), id == null ? 0 : ledger.roomFor(catalog, id),
                    id == null ? 0 : ledger.bossPercentOf(id), id == null ? 0 : loadItems(playerId, FieldResearch.Item.BOSS_SAMPLE).getOrDefault(id, 0),
                    zoneTier, loadStudy("player_id", playerId).isPresent(), loadStudy("pokemon_id", pokemonId).isPresent(), options.clock().millis());
            var check = BenchStudies.startCheck(request);
            if (check != BenchStudies.StartCheck.OK) throw new ResearchException(ResearchException.Reason.STUDY, check.name());
            var study = BenchStudies.start(request, jobId);
            if (kind == BenchStudies.Kind.ANALYSIS) takeItem(playerId, FieldResearch.Item.BOSS_SAMPLE, id, 1);
            try (var statement = db.prepareStatement(
                    "INSERT INTO research_jobs(job_id, player_id, pokemon_id, species, kind, started_at, ends_at) VALUES(?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, study.jobId().toString());
                statement.setString(2, playerId.toString());
                statement.setString(3, pokemonId.toString());
                statement.setString(4, study.species());
                statement.setString(5, kind.name());
                statement.setLong(6, study.startedMillis());
                statement.setLong(7, study.endsMillis());
                statement.executeUpdate();
            }
            return new Fresh(Kind.RESEARCH_STUDY, playerId, pokemonId, null, null, Map.of(), 0).moved(Map.of(), Map.of(), "start:" + kind + ":" + id);
        });
    }

    /**
     * Collects the player's finished study: pays it (research for Quick and Standard, boss research for an analysis) and frees the Pokemon, in
     * one transaction under the study's own operation ({@link BenchStudies#payOperation}). Refused when there is no study or it has not
     * finished on the server clock.
     */
    public synchronized Outcome collectResearchStudy(UUID playerId, ResearchFamilies.Catalog catalog) {
        var study = read(() -> loadStudy("player_id", playerId)).orElseThrow(() -> new ResearchException(ResearchException.Reason.NOTHING_TO_COLLECT, ""));
        if (!study.finished(options.clock().millis()))
            throw new ResearchException(ResearchException.Reason.NOT_FINISHED, Long.toString(study.remainingMillis(options.clock().millis())));
        return run(BenchStudies.payOperation(study), "RESEARCH_COLLECT|" + study.jobId(), () -> {
            // Re-read inside the transaction: the study must still be the same one.
            var current = loadStudy("player_id", playerId);
            if (current.isEmpty() || !current.get().jobId().equals(study.jobId()))
                throw new ResearchException(ResearchException.Reason.NOTHING_TO_COLLECT, "");
            if (study.kind() == BenchStudies.Kind.ANALYSIS) {
                var ledger = loadResearch(playerId);
                if (catalog.knows(study.species()) && ledger.bossPercentOf(study.species()) < FieldResearch.BOSS_MAX_PERCENT)
                    saveResearch(playerId, ledger.analyze(catalog, study.species()).ledger(), study.species());
            } else if (catalog.knows(study.species())) {
                creditResearch(playerId, catalog, study.species(), study.kind().points);
            }
            deleteStudy(study.jobId());
            return new Fresh(Kind.RESEARCH_STUDY, playerId, study.pokemon(), null, null, Map.of(), 0).moved(Map.of(), Map.of(), "collect:" + study.kind() + ":" + study.species());
        });
    }

    /** Recalls the Pokemon from the player's study early: the study ends and pays nothing (an analysis' sample is lost). */
    public synchronized Outcome recallResearchStudy(UUID operationId, UUID playerId) {
        return run(operationId, "RESEARCH_RECALL|" + playerId, () -> {
            var study = loadStudy("player_id", playerId).orElseThrow(() -> new ResearchException(ResearchException.Reason.NOTHING_TO_COLLECT, ""));
            deleteStudy(study.jobId());
            return new Fresh(Kind.RESEARCH_STUDY, playerId, study.pokemon(), null, null, Map.of(), 0).moved(Map.of(), Map.of(), "recall:" + study.kind() + ":" + study.species());
        });
    }

    /**
     * Crafts serums: the domain's craft check against the player's research, this period's count and their Boss Samples; the wallet cost and
     * the samples are taken, the serums credited to the wallet (tradable, decision 8) and the counter raised, all in one transaction, so two
     * crafts at once cannot both slip under the limit. Samples are taken from species already at 100% boss research first, then the most held.
     */
    public synchronized Outcome craftSerum(UUID operationId, UUID playerId, ResearchFamilies.Catalog catalog, Serums.Kind kind, int quantity,
                                           long expectedWalletRevision) {
        Objects.requireNonNull(kind);
        return run(operationId, "SERUM_CRAFT|" + playerId + "|" + kind.id + "|" + quantity + "|" + expectedWalletRevision, () -> {
            long period = kind.periodOf(options.clock().millis());
            var ledger = loadResearch(playerId);
            var samples = loadItems(playerId, FieldResearch.Item.BOSS_SAMPLE);
            int held = samples.values().stream().mapToInt(Integer::intValue).sum();
            var check = Serums.craftCheck(kind, ledger.highestLevel(catalog), counter(playerId, kind.id, period), held, quantity);
            if (check != Serums.CraftCheck.OK) throw new ResearchException(ResearchException.Reason.CRAFT, check.name());
            var before = loadWallet(playerId);
            if (expectedWalletRevision != CraftRequest.ANY_REVISION && before.revision() != expectedWalletRevision)
                throw new StoreException(Code.STALE_WALLET, "Wallet changed since it was shown");
            var cost = Serums.costOf(kind, quantity);
            var after = before.debit(cost).credit(Map.of(kind.material(), (long) quantity));
            options.faults().at(Faults.Point.AFTER_VALIDATION);
            int need = kind.bossSamples * quantity;
            var order = new ArrayList<>(samples.keySet());
            order.sort(Comparator.<String>comparingInt(s -> ledger.bossPercentOf(s) >= FieldResearch.BOSS_MAX_PERCENT ? 0 : 1)
                    .thenComparing(s -> -samples.get(s)).thenComparing(s -> s));
            for (var species : order) {
                if (need == 0) break;
                int take = Math.min(need, samples.get(species));
                takeItem(playerId, FieldResearch.Item.BOSS_SAMPLE, species, take);
                need -= take;
            }
            saveWallet(playerId, before, after);
            bumpCounter(playerId, kind.id, period, quantity);
            return new Fresh(Kind.SERUM, playerId, null, null, after, cost, 0).moved(cost, Map.of(kind.material(), (long) quantity), "craft:" + kind.id);
        });
    }

    /**
     * Arms a serum from the wallet for one family (decision 6 and follow-up 1): the player's research on that family must be the serum's level,
     * the chosen stats valid and distinct, and no serum already armed for it. The serum leaves the wallet in the same transaction.
     *
     * @param familyExcluded whether every species of the family is excluded (the runtime knows the labels; Ditto's family, a legendary line)
     */
    public synchronized Outcome armSerum(UUID operationId, UUID playerId, ResearchFamilies.Catalog catalog, Serums.Kind kind, String familyId,
                                         List<Serums.Stat> chosen, boolean familyExcluded) {
        Objects.requireNonNull(kind);
        String family = ResearchFamilies.normalise(familyId);
        var stats = chosen == null ? List.<Serums.Stat>of() : new ArrayList<>(chosen);
        return run(operationId, "SERUM_ARM|" + playerId + "|" + kind.id + "|" + family + "|" + statsText(stats) + "|" + familyExcluded, () -> {
            boolean armed = armedFor(playerId, family);
            var check = Serums.armCheck(kind, catalog, family, stats, loadResearch(playerId).familyLevel(catalog, family), armed, familyExcluded);
            if (check != Serums.ArmCheck.OK) throw new ResearchException(ResearchException.Reason.ARM, check.name());
            var before = loadWallet(playerId);
            var cost = Map.of(kind.material(), 1L);
            var after = before.debit(cost);
            options.faults().at(Faults.Point.AFTER_VALIDATION);
            saveWallet(playerId, before, after);
            try (var statement = db.prepareStatement(
                    "INSERT INTO research_armed(player_id, family_id, kind, stats, arm_operation, armed_at) VALUES(?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, family);
                statement.setString(3, kind.id);
                statement.setString(4, statsText(stats));
                statement.setString(5, operationId.toString());
                statement.setLong(6, options.clock().millis());
                statement.executeUpdate();
            }
            return new Fresh(Kind.SERUM, playerId, null, null, after, cost, 0).moved(cost, Map.of(), "arm:" + kind.id + ":" + family);
        });
    }

    /**
     * Disarms the serum armed for a family and puts it back in the wallet. Always allowed, also for a family a catalog change removed, so an
     * armed serum can never be stranded.
     */
    public synchronized Outcome disarmSerum(UUID operationId, UUID playerId, String familyId) {
        String family = ResearchFamilies.normalise(familyId);
        return run(operationId, "SERUM_DISARM|" + playerId + "|" + family, () -> {
            String kindId = null;
            try (var statement = db.prepareStatement("SELECT kind FROM research_armed WHERE player_id = ? AND family_id = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, family);
                try (var rows = statement.executeQuery()) { if (rows.next()) kindId = rows.getString(1); }
            }
            if (kindId == null) throw new ResearchException(ResearchException.Reason.NOT_ARMED, family);
            var kind = Serums.Kind.fromId(kindId);
            try (var statement = db.prepareStatement("DELETE FROM research_armed WHERE player_id = ? AND family_id = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, family);
                statement.executeUpdate();
            }
            var before = loadWallet(playerId);
            var refund = Map.of(kind.material(), 1L);
            var after = before.credit(refund);
            saveWallet(playerId, before, after);
            return new Fresh(Kind.SERUM, playerId, null, null, after, Map.of(), 0).moved(Map.of(), refund, "disarm:" + kind.id + ":" + family);
        });
    }

    /** What a serum did at a capture, as recorded: the IVs to write and the stats it raised. */
    public record SerumUse(UUID pokemonId, UUID playerId, Serums.Kind kind, int[] ivs, List<Serums.Stat> raised, boolean projected) {
        public SerumUse {
            ivs = ivs.clone();
            raised = List.copyOf(raised);
        }

        @Override public int[] ivs() { return ivs.clone(); }
    }

    /**
     * A wild capture: if the player has a serum armed for the caught Pokemon's family and it applies ({@link Serums#apply}, checked again here
     * with the player's current research), the serum is consumed and the IVs to write are recorded, in one transaction keyed by the Pokemon
     * (one serum per capture, a repeated call returns the same result). Empty when nothing applies; the serum then stays armed.
     */
    public synchronized Optional<SerumUse> useArmedSerum(UUID playerId, ResearchFamilies.Catalog catalog, Serums.CaptureFacts capture,
                                                         UUID pokemonId, int[] ivs) {
        Objects.requireNonNull(pokemonId);
        var recorded = read(() -> loadSerumUse(pokemonId));
        if (recorded.isPresent()) return recorded;
        if (capture == null || ivs == null) return Optional.empty();
        var family = catalog.familyOf(capture.species());
        if (family.isEmpty()) return Optional.empty();
        var armed = armedSerums(playerId).get(family.get().id());
        if (armed == null) return Optional.empty();
        String armOperation = read(() -> {
            try (var statement = db.prepareStatement("SELECT arm_operation FROM research_armed WHERE player_id = ? AND family_id = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, family.get().id());
                try (var rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
            }
        });
        if (armOperation == null) return Optional.empty();
        var outcome = Serums.apply(armed, catalog, capture, loadResearchUnlocked(playerId).familyLevel(catalog, family.get().id()), ivs,
                Serums.seed(pokemonId, UUID.fromString(armOperation)));
        if (!outcome.applied()) return Optional.empty();
        var operation = FieldResearch.operation("serum-use", pokemonId.toString(), playerId);
        run(operation, "SERUM_USE|" + playerId + "|" + pokemonId + "|" + armed.kind().id + "|" + Arrays.toString(outcome.ivs()), () -> {
            if (loadSerumUse(pokemonId).isPresent()) throw new ResearchException(ResearchException.Reason.NOT_ARMED, "already used on this Pokemon");
            try (var statement = db.prepareStatement("DELETE FROM research_armed WHERE player_id = ? AND family_id = ? AND arm_operation = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, family.get().id());
                statement.setString(3, armOperation);
                if (statement.executeUpdate() != 1) throw new ResearchException(ResearchException.Reason.NOT_ARMED, family.get().id());
            }
            try (var statement = db.prepareStatement(
                    "INSERT INTO research_serum_uses(pokemon_id, player_id, kind, ivs, raised, projected, used_at) VALUES(?, ?, ?, ?, ?, 0, ?)")) {
                statement.setString(1, pokemonId.toString());
                statement.setString(2, playerId.toString());
                statement.setString(3, armed.kind().id);
                statement.setString(4, ivsText(outcome.ivs()));
                statement.setString(5, statsText(outcome.raised()));
                statement.setLong(6, options.clock().millis());
                statement.executeUpdate();
            }
            return new Fresh(Kind.SERUM, playerId, pokemonId, null, null, Map.of(), 0).moved(Map.of(), Map.of(), "use:" + armed.kind().id + ":" + family.get().id());
        });
        return read(() -> loadSerumUse(pokemonId));
    }

    /** Serum results whose IVs were never confirmed written (a crash, an offline owner): write them again, then {@link #markSerumUseProjected}. */
    public synchronized List<SerumUse> unprojectedSerumUses() {
        return read(() -> {
            var out = new ArrayList<SerumUse>();
            try (var statement = db.createStatement(); var rows = statement.executeQuery(
                    "SELECT pokemon_id, player_id, kind, ivs, raised, projected FROM research_serum_uses WHERE projected = 0")) {
                while (rows.next()) out.add(readSerumUse(rows));
            }
            return out;
        });
    }

    /** Records that a serum's IVs were written to the Pokemon. Safe to repeat. */
    public synchronized void markSerumUseProjected(UUID pokemonId) {
        transaction(() -> {
            try (var statement = db.prepareStatement("UPDATE research_serum_uses SET projected = 1 WHERE pokemon_id = ?")) {
                statement.setString(1, pokemonId.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** The serum recorded for a Pokemon, if one was ever used on it (the runtime writes the {@code ascensionlib_serum} mark from this). */
    public synchronized Optional<SerumUse> serumUse(UUID pokemonId) {
        return read(() -> loadSerumUse(pokemonId));
    }

    // research row access

    private String knownSpecies(ResearchFamilies.Catalog catalog, String species) {
        String id = ResearchFamilies.normalise(species);
        if (id == null || !catalog.knows(id)) throw new ResearchException(ResearchException.Reason.UNKNOWN_SPECIES, String.valueOf(species));
        return id;
    }

    private ResearchLedger loadResearch(UUID playerId) throws SQLException {
        var points = new HashMap<String, Integer>();
        var boss = new HashMap<String, Integer>();
        try (var statement = db.prepareStatement("SELECT species, points, boss_percent FROM research_awards WHERE player_id = ?")) {
            statement.setString(1, playerId.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    points.put(rows.getString(1), rows.getInt(2));
                    boss.put(rows.getString(1), rows.getInt(3));
                }
            }
        }
        return new ResearchLedger(points, boss);
    }

    /** {@link #loadResearch} for code outside a transaction. */
    private ResearchLedger loadResearchUnlocked(UUID playerId) {
        return read(() -> loadResearch(playerId));
    }

    private void creditResearch(UUID playerId, ResearchFamilies.Catalog catalog, String species, int amount) throws SQLException {
        var credit = loadResearch(playerId).credit(catalog, species, amount);
        if (credit.applied() > 0) saveResearch(playerId, credit.ledger(), species);
    }

    /** Writes one species' row from the ledger (points capped by the ledger, boss research clamped by it). */
    private void saveResearch(UUID playerId, ResearchLedger ledger, String species) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO research_awards(player_id, species, points, boss_percent) VALUES(?, ?, ?, ?)"
                + " ON CONFLICT(player_id, species) DO UPDATE SET points = excluded.points, boss_percent = excluded.boss_percent")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, species);
            statement.setInt(3, ledger.speciesPoints().getOrDefault(species, 0));
            statement.setInt(4, ledger.bossPercentOf(species));
            statement.executeUpdate();
        }
    }

    private Map<String, Integer> loadItems(UUID playerId, FieldResearch.Item item) throws SQLException {
        var out = new TreeMap<String, Integer>();
        try (var statement = db.prepareStatement("SELECT species, count FROM research_items WHERE player_id = ? AND item = ? AND count > 0")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, item.id);
            try (var rows = statement.executeQuery()) { while (rows.next()) out.put(rows.getString(1), rows.getInt(2)); }
        }
        return out;
    }

    private void addItem(UUID playerId, FieldResearch.Item item, String species, int count) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO research_items(player_id, item, species, count) VALUES(?, ?, ?, ?)"
                + " ON CONFLICT(player_id, item, species) DO UPDATE SET count = count + excluded.count")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, item.id);
            statement.setString(3, species);
            statement.setInt(4, count);
            statement.executeUpdate();
        }
    }

    /** Takes {@code count} items; refused (nothing written) when the player holds fewer. */
    private void takeItem(UUID playerId, FieldResearch.Item item, String species, int count) throws SQLException {
        try (var statement = db.prepareStatement("UPDATE research_items SET count = count - ? WHERE player_id = ? AND item = ? AND species = ? AND count >= ?")) {
            statement.setInt(1, count);
            statement.setString(2, playerId.toString());
            statement.setString(3, item.id);
            statement.setString(4, species);
            statement.setInt(5, count);
            if (statement.executeUpdate() != 1) throw new ResearchException(ResearchException.Reason.NO_ITEM, item.id + ":" + species);
        }
    }

    private Optional<BenchStudies.Study> loadStudy(String column, UUID id) throws SQLException {
        // column is one of two constants in this class, never caller text
        if (!column.equals("player_id") && !column.equals("pokemon_id")) throw new IllegalArgumentException(column);
        try (var statement = db.prepareStatement("SELECT job_id, player_id, pokemon_id, species, kind, started_at, ends_at FROM research_jobs WHERE " + column + " = ?")) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new BenchStudies.Study(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), UUID.fromString(rows.getString(3)),
                        rows.getString(4), BenchStudies.Kind.valueOf(rows.getString(5)), rows.getLong(6), rows.getLong(7)));
            }
        }
    }

    private void deleteStudy(UUID jobId) throws SQLException {
        try (var statement = db.prepareStatement("DELETE FROM research_jobs WHERE job_id = ?")) {
            statement.setString(1, jobId.toString());
            statement.executeUpdate();
        }
    }

    private int counter(UUID playerId, String counter, long period) throws SQLException {
        try (var statement = db.prepareStatement("SELECT used FROM research_days WHERE player_id = ? AND counter = ? AND period = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, counter);
            statement.setLong(3, period);
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
        }
    }

    private void bumpCounter(UUID playerId, String counter, long period, int by) throws SQLException {
        try (var statement = db.prepareStatement("INSERT INTO research_days(player_id, counter, period, used) VALUES(?, ?, ?, ?)"
                + " ON CONFLICT(player_id, counter, period) DO UPDATE SET used = used + excluded.used")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, counter);
            statement.setLong(3, period);
            statement.setInt(4, by);
            statement.executeUpdate();
        }
    }

    private boolean armedFor(UUID playerId, String family) throws SQLException {
        try (var statement = db.prepareStatement("SELECT 1 FROM research_armed WHERE player_id = ? AND family_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, family);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private Optional<SerumUse> loadSerumUse(UUID pokemonId) throws SQLException {
        try (var statement = db.prepareStatement("SELECT pokemon_id, player_id, kind, ivs, raised, projected FROM research_serum_uses WHERE pokemon_id = ?")) {
            statement.setString(1, pokemonId.toString());
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(readSerumUse(rows)) : Optional.empty(); }
        }
    }

    private static SerumUse readSerumUse(ResultSet rows) throws SQLException {
        return new SerumUse(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), Serums.Kind.fromId(rows.getString(3)),
                parseIvs(rows.getString(4)), stats(rows.getString(5)), rows.getInt(6) == 1);
    }

    private static String statsText(List<Serums.Stat> stats) {
        var names = new ArrayList<String>();
        for (var stat : stats) names.add(stat == null ? "null" : stat.name());
        return String.join(",", names);
    }

    private static List<Serums.Stat> stats(String text) {
        var out = new ArrayList<Serums.Stat>();
        if (text != null && !text.isEmpty()) for (var part : text.split(",")) out.add(Serums.Stat.valueOf(part));
        return out;
    }

    private static String ivsText(int[] ivs) {
        var parts = new ArrayList<String>();
        for (int v : ivs) parts.add(Integer.toString(v));
        return String.join(",", parts);
    }

    private static int[] parseIvs(String text) {
        var parts = text.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }

    // --- transaction machinery ------------------------------------------------------------------------

    private Outcome run(UUID operationId, String canonicalRequest, Work<Fresh> work) {
        Objects.requireNonNull(operationId);
        String hash = sha256(canonicalRequest);
        long started = System.nanoTime();
        var committed = new Fresh[1];
        var outcome = transaction(() -> {
            var prior = lookupOperation(operationId);
            if (prior != null) {
                if (!prior.hash().equals(hash))
                    throw new StoreException(Code.OPERATION_REUSED, "Operation ID was used for a different request");
                return decodeOutcome(operationId, prior).asReplay();
            }
            var fresh = work.run();
            committed[0] = fresh;
            String result = encodeOutcome(fresh);
            try (var statement = db.prepareStatement("""
                    INSERT INTO operations(operation_id, request_hash, kind, pokemon_id, committed_at, result)
                    VALUES(?, ?, ?, ?, ?, ?)""")) {
                statement.setString(1, operationId.toString());
                statement.setString(2, hash);
                statement.setString(3, fresh.kind().name());
                statement.setString(4, fresh.pokemonId() == null ? null : fresh.pokemonId().toString());
                statement.setLong(5, options.clock().millis());
                statement.setString(6, result);
                statement.executeUpdate();
            }
            return new Outcome(operationId, fresh.kind(), fresh.profile(), fresh.wallet(), fresh.cost(),
                    fresh.creditsSpent(), false);
        });
        notifyCommitted(committed[0], operationId, System.nanoTime() - started);
        return outcome;
    }

    private volatile Consumer<Committed> commitListener;

    /**
     * Reports every operation this store commits, once it is durable, to {@code listener} (null stops it). A replay is not reported: it changed nothing.
     * The listener runs on the committing thread while the store is locked, so it must only record and return; its failures are swallowed so a
     * logging fault can never undo or fail an economy change.
     */
    public void onCommit(Consumer<Committed> listener) { this.commitListener = listener; }

    private void notifyCommitted(Fresh fresh, UUID operationId, long nanos) {
        var listener = commitListener;
        if (listener == null || fresh == null) return;
        try {
            listener.accept(new Committed(fresh.kind(), operationId, fresh.playerId(), fresh.pokemonId(), fresh.detail(), fresh.spent(), fresh.gained(),
                    fresh.creditsSpent(), fresh.profile(), fresh.wallet(), nanos));
        } catch (RuntimeException ignored) {
            // observation only
        }
    }

    /**
     * Total of each material across every wallet: the economy's money supply, for balancing. One aggregate query; the balances of players who
     * never come back count as well.
     */
    public synchronized Map<MaterialId, Long> materialSupply() {
        var supply = new EnumMap<MaterialId, Long>(MaterialId.class);
        try (var statement = db.prepareStatement("SELECT material_id, SUM(amount) FROM wallet_balances GROUP BY material_id");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    supply.put(MaterialId.fromId(rows.getString(1)), rows.getLong(2));
                } catch (IllegalArgumentException unknown) {
                    // a material written by a newer build
                }
            }
        } catch (SQLException exception) {
            throw new StoreException(Code.STORE_UNAVAILABLE, "Cannot total the wallets", exception);
        }
        return supply;
    }

    /** How many wallets exist (players who ever held a material). */
    public synchronized int walletCount() {
        try (var statement = db.prepareStatement("SELECT COUNT(*) FROM wallets"); var rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        } catch (SQLException exception) {
            throw new StoreException(Code.STORE_UNAVAILABLE, "Cannot count the wallets", exception);
        }
    }

    private <T> T transaction(Work<T> work) {
        T result;
        try {
            result = work.run();
            options.faults().at(Faults.Point.BEFORE_COMMIT);
            db.commit();
        } catch (RuntimeException | Error exception) {
            rollbackQuietly();
            throw exception;
        } catch (SQLException exception) {
            rollbackQuietly();
            throw new StoreException(Code.STORE_UNAVAILABLE, "Progression store write failed", exception);
        }
        options.faults().at(Faults.Point.AFTER_COMMIT);
        return result;
    }

    private void rollbackQuietly() {
        try { db.rollback(); } catch (SQLException ignored) { }
    }

    // --- row access -----------------------------------------------------------------------------------

    private Optional<ProfileV1> loadProfile(UUID pokemonId) throws SQLException {
        try (var statement = db.prepareStatement("SELECT body FROM profiles WHERE pokemon_id = ?")) {
            statement.setString(1, pokemonId.toString());
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(decodeProfile(rows.getString(1), pokemonId)) : Optional.empty();
            }
        }
    }

    private ProfileV1 decodeProfile(String body, UUID pokemonId) {
        try {
            return codec.decode(body, pokemonId);
        } catch (RuntimeException exception) {
            throw new StoreException(Code.STORED_DATA_INVALID, "Stored profile failed validation (preserved)", exception);
        }
    }

    private void insertProfile(ProfileV1 profile) throws SQLException {
        try (var statement = db.prepareStatement(
                "INSERT INTO profiles(profile_id, pokemon_id, revision, body) VALUES(?, ?, ?, ?)")) {
            statement.setString(1, profile.profileId().toString());
            statement.setString(2, profile.pokemonId().toString());
            statement.setLong(3, profile.revision());
            statement.setString(4, codec.encode(profile));
            statement.executeUpdate();
        }
    }

    private void updateProfile(ProfileV1 next, long expectedRevision) throws SQLException {
        try (var statement = db.prepareStatement(
                "UPDATE profiles SET revision = ?, body = ? WHERE profile_id = ? AND revision = ?")) {
            statement.setLong(1, next.revision());
            statement.setString(2, codec.encode(next));
            statement.setString(3, next.profileId().toString());
            statement.setLong(4, expectedRevision);
            if (statement.executeUpdate() != 1)
                throw new StoreException(Code.STALE_PROFILE, "Profile changed during the operation");
        }
    }

    private void recordMilestones(UUID profileId, Collection<Integer> levels, UUID operationId) throws SQLException {
        for (int level : levels) {
            try (var statement = db.prepareStatement(
                    "INSERT INTO milestone_awards(profile_id, milestone_level, operation_id) VALUES(?, ?, ?)")) {
                statement.setString(1, profileId.toString());
                statement.setInt(2, level);
                statement.setString(3, operationId.toString());
                statement.executeUpdate();
            }
        }
    }

    private String migrationDigest(UUID profileId) throws SQLException {
        try (var statement = db.prepareStatement("SELECT source_digest FROM migrations WHERE profile_id = ?")) {
            statement.setString(1, profileId.toString());
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    private MaterialWallet loadWallet(UUID playerId) throws SQLException {
        long revision;
        try (var statement = db.prepareStatement("SELECT revision FROM wallets WHERE player_id = ?")) {
            statement.setString(1, playerId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return MaterialWallet.EMPTY;
                revision = rows.getLong(1);
            }
        }
        var balances = new EnumMap<MaterialId, Long>(MaterialId.class);
        try (var statement = db.prepareStatement("SELECT material_id, amount FROM wallet_balances WHERE player_id = ?")) {
            statement.setString(1, playerId.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        balances.put(MaterialId.fromId(rows.getString(1)), rows.getLong(2));
                    } catch (IllegalArgumentException exception) {
                        throw new StoreException(Code.STORED_DATA_INVALID, "Stored wallet has an unknown material", exception);
                    }
                }
            }
        }
        return new MaterialWallet(revision, balances);
    }

    private void saveWallet(UUID playerId, MaterialWallet before, MaterialWallet after) throws SQLException {
        if (before.revision() == 0) {
            try (var statement = db.prepareStatement("INSERT INTO wallets(player_id, revision) VALUES(?, ?)")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, after.revision());
                statement.executeUpdate();
            }
        } else {
            try (var statement = db.prepareStatement("UPDATE wallets SET revision = ? WHERE player_id = ? AND revision = ?")) {
                statement.setLong(1, after.revision());
                statement.setString(2, playerId.toString());
                statement.setLong(3, before.revision());
                if (statement.executeUpdate() != 1)
                    throw new StoreException(Code.STALE_WALLET, "Wallet changed during the operation");
            }
        }
        for (var id : MaterialId.values()) {
            try (var statement = db.prepareStatement("""
                    INSERT INTO wallet_balances(player_id, material_id, amount) VALUES(?, ?, ?)
                    ON CONFLICT(player_id, material_id) DO UPDATE SET amount = excluded.amount""")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, id.id());
                statement.setLong(3, after.balance(id));
                statement.executeUpdate();
            }
        }
    }

    private Recorded lookupOperation(UUID operationId) throws SQLException {
        try (var statement = db.prepareStatement(
                "SELECT request_hash, kind, pokemon_id, result FROM operations WHERE operation_id = ?")) {
            statement.setString(1, operationId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                String pokemon = rows.getString(3);
                return new Recorded(rows.getString(1), Kind.valueOf(rows.getString(2)),
                        pokemon == null ? null : UUID.fromString(pokemon), rows.getString(4));
            }
        }
    }

    // --- result encoding ------------------------------------------------------------------------------

    private String encodeOutcome(Fresh fresh) {
        var root = new JsonObject();
        root.addProperty("kind", fresh.kind().name());
        if (fresh.profile() != null) root.add("profile", JsonParser.parseString(codec.encode(fresh.profile())));
        if (fresh.wallet() != null) {
            var wallet = new JsonObject();
            wallet.addProperty("revision", fresh.wallet().revision());
            var balances = new JsonObject();
            for (var id : MaterialId.values()) balances.addProperty(id.id(), fresh.wallet().balance(id));
            wallet.add("balances", balances);
            root.add("wallet", wallet);
        }
        var cost = new JsonObject();
        new TreeMap<String, Long>(toIds(fresh.cost())).forEach(cost::addProperty);
        root.add("cost", cost);
        root.addProperty("creditsSpent", fresh.creditsSpent());
        return root.toString();
    }

    private static Map<String, Long> toIds(Map<MaterialId, Long> amounts) {
        var result = new HashMap<String, Long>();
        amounts.forEach((id, amount) -> result.put(id.id(), amount));
        return result;
    }

    private Outcome decodeOutcome(UUID operationId, Recorded recorded) {
        try {
            var root = JsonParser.parseString(recorded.result()).getAsJsonObject();
            ProfileV1 profile = null;
            if (root.has("profile")) profile = codec.decode(root.get("profile").toString(), recorded.pokemonId());
            MaterialWallet wallet = null;
            if (root.has("wallet")) {
                var object = root.getAsJsonObject("wallet");
                var balances = new EnumMap<MaterialId, Long>(MaterialId.class);
                object.getAsJsonObject("balances").entrySet()
                        .forEach(e -> balances.put(MaterialId.fromId(e.getKey()), e.getValue().getAsLong()));
                wallet = new MaterialWallet(object.get("revision").getAsLong(), balances);
            }
            var cost = new EnumMap<MaterialId, Long>(MaterialId.class);
            root.getAsJsonObject("cost").entrySet()
                    .forEach(e -> cost.put(MaterialId.fromId(e.getKey()), e.getValue().getAsLong()));
            return new Outcome(operationId, recorded.kind(), profile, wallet, cost,
                    root.get("creditsSpent").getAsInt(), false);
        } catch (RuntimeException exception) {
            throw new StoreException(Code.STORED_DATA_INVALID, "Recorded operation result is unreadable (preserved)", exception);
        }
    }

    private static String sha256(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
