using Aevum.PcTracker.Core.Models;
using Microsoft.Data.Sqlite;

namespace Aevum.PcTracker.Core.Services;

/// <summary>
/// SQLite-Speicher: Sessions, Regeln, erkannter Programm-Katalog und Einstellungen.
/// Alle Zugriffe laufen über ein internes Lock (eine Connection, kein Pool-Chaos);
/// die Datenmengen sind klein, die Einfachheit gewinnt.
/// </summary>
public sealed class SessionStore : IDisposable
{
    private readonly string _dbPath;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private SqliteConnection? _conn;

    public SessionStore(string dbPath)
    {
        _dbPath = dbPath;
    }

    public string DbPath => _dbPath;

    public void Open()
    {
        var dir = Path.GetDirectoryName(_dbPath);
        if (!string.IsNullOrEmpty(dir))
        {
            Directory.CreateDirectory(dir);
        }
        _conn = new SqliteConnection($"Data Source={_dbPath}");
        _conn.Open();
        Exec("PRAGMA journal_mode=WAL;");
        Exec("PRAGMA synchronous=NORMAL;");
        Exec("PRAGMA foreign_keys=ON;");
        Migrate();
    }

    private void Migrate()
    {
        Exec("""
            CREATE TABLE IF NOT EXISTS sessions (
              id            TEXT PRIMARY KEY,
              program_key   TEXT NOT NULL,
              program_name  TEXT NOT NULL,
              activity_key  TEXT NOT NULL,
              start_at      INTEGER NOT NULL,
              end_at        INTEGER NULL,
              last_seen_at  INTEGER NOT NULL,
              timezone_id   TEXT NOT NULL,
              created_at    INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_sessions_end ON sessions(end_at);
            CREATE INDEX IF NOT EXISTS idx_sessions_start ON sessions(start_at);

            CREATE TABLE IF NOT EXISTS rules (
              program_key       TEXT PRIMARY KEY,
              program_name      TEXT NOT NULL,
              activity_key      TEXT NOT NULL,
              threshold_seconds INTEGER NOT NULL,
              enabled           INTEGER NOT NULL,
              created_at        INTEGER NOT NULL,
              updated_at        INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS programs (
              program_key   TEXT PRIMARY KEY,
              name          TEXT NOT NULL,
              path          TEXT NOT NULL DEFAULT '',
              first_seen_at INTEGER NOT NULL,
              last_seen_at  INTEGER NOT NULL,
              hidden        INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS settings (
              key   TEXT PRIMARY KEY,
              value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS synced_activities (
              key          TEXT PRIMARY KEY,
              name         TEXT NOT NULL,
              icon         TEXT NOT NULL DEFAULT '',
              category_key TEXT NOT NULL DEFAULT '',
              updated_at   INTEGER NOT NULL
            );
            """);
        // Bestands-Datenbanken (PC-Tracker 1.0): Regeln bekommen den Aufnahme-Modus.
        if (!ColumnExists("rules", "record_mode"))
        {
            Exec("ALTER TABLE rules ADD COLUMN record_mode INTEGER NOT NULL DEFAULT 0;");
        }
        if (GetSetting("schema_version") is null)
        {
            SetSetting("schema_version", "2");
        }
    }

    // ---------------------------------------------------------------- Sessions

    /// <summary>Wendet Engine-Ergebnisse an (eine Transaktion, Upsert je Session).</summary>
    public void Apply(IReadOnlyList<TrackedSession> sessions)
    {
        if (sessions.Count == 0)
        {
            return;
        }
        WithDb(conn =>
        {
            using var tx = conn.BeginTransaction();
            foreach (var s in sessions)
            {
                using var cmd = conn.CreateCommand();
                cmd.Transaction = tx;
                cmd.CommandText = """
                    INSERT INTO sessions (id, program_key, program_name, activity_key, start_at, end_at, last_seen_at, timezone_id, created_at)
                    VALUES ($id, $pk, $pn, $ak, $sa, $ea, $ls, $tz, $ca)
                    ON CONFLICT(id) DO UPDATE SET end_at = excluded.end_at, last_seen_at = excluded.last_seen_at
                    """;
                cmd.Parameters.AddWithValue("$id", s.Id);
                cmd.Parameters.AddWithValue("$pk", s.ProgramKey);
                cmd.Parameters.AddWithValue("$pn", s.ProgramName);
                cmd.Parameters.AddWithValue("$ak", s.ActivityKey);
                cmd.Parameters.AddWithValue("$sa", s.StartAtMs);
                cmd.Parameters.AddWithValue("$ea", (object?)s.EndAtMs ?? DBNull.Value);
                cmd.Parameters.AddWithValue("$ls", s.LastSeenAtMs);
                cmd.Parameters.AddWithValue("$tz", s.TimezoneId);
                cmd.Parameters.AddWithValue("$ca", s.CreatedAtMs);
                cmd.ExecuteNonQuery();
            }
            tx.Commit();
            return 0;
        });
    }

    /// <summary>
    /// Repariert Sessions, die beim Beenden der App offen geblieben sind:
    /// ihr Ende ist der letzte gesehene Zeitpunkt. Wird beim Start aufgerufen.
    /// </summary>
    public int RepairOpenSessions()
    {
        return WithDb(conn =>
        {
            using var cmd = conn.CreateCommand();
            cmd.CommandText = """
                UPDATE sessions
                SET end_at = MAX(last_seen_at, start_at)
                WHERE end_at IS NULL
                """;
            return cmd.ExecuteNonQuery();
        });
    }

    /// <summary>Abgeschlossene Sessions mit endAt &gt; sinceMs (aufsteigend, stabil sortiert).</summary>
    public List<TrackedSession> QueryCompletedSessions(long sinceMs, int limit)
    {
        return WithDb(conn =>
        {
            using var cmd = conn.CreateCommand();
            cmd.CommandText = """
                SELECT id, program_key, program_name, activity_key, start_at, end_at, last_seen_at, timezone_id, created_at
                FROM sessions
                WHERE end_at IS NOT NULL AND end_at > $since
                ORDER BY end_at ASC, id ASC
                LIMIT $limit
                """;
            cmd.Parameters.AddWithValue("$since", sinceMs);
            cmd.Parameters.AddWithValue("$limit", Math.Clamp(limit, 1, 500));
            using var reader = cmd.ExecuteReader();
            var list = new List<TrackedSession>();
            while (reader.Read())
            {
                list.Add(ReadSession(reader));
            }
            return list;
        });
    }

    /// <summary>Sessions für die Verlaufsansicht: neueste zuerst ab sinceMs (oder alles).</summary>
    public List<TrackedSession> QueryRecentSessions(long? sinceMs, int limit)
    {
        return WithDb(conn =>
        {
            using var cmd = conn.CreateCommand();
            cmd.CommandText = """
                SELECT id, program_key, program_name, activity_key, start_at, end_at, last_seen_at, timezone_id, created_at
                FROM sessions
                WHERE end_at IS NOT NULL AND ($since IS NULL OR end_at > $since)
                ORDER BY start_at DESC
                LIMIT $limit
                """;
            cmd.Parameters.AddWithValue("$since", (object?)sinceMs ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$limit", Math.Clamp(limit, 1, 5000));
            using var reader = cmd.ExecuteReader();
            var list = new List<TrackedSession>();
            while (reader.Read())
            {
                list.Add(ReadSession(reader));
            }
            return list;
        });
    }

    public int CountSessions() => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM sessions";
        return Convert.ToInt32(cmd.ExecuteScalar());
    });

    /// <summary>Löscht abgeschlossene Sessions, die vor cutoffMs endeten. Gibt die Anzahl zurück.</summary>
    public int PurgeSessionsBefore(long cutoffMs) => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "DELETE FROM sessions WHERE end_at IS NOT NULL AND end_at < $cutoff";
        cmd.Parameters.AddWithValue("$cutoff", cutoffMs);
        return cmd.ExecuteNonQuery();
    });

    /// <summary>Löscht alle Sessions („Daten zurücksetzen").</summary>
    public int DeleteAllSessions() => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "DELETE FROM sessions";
        return cmd.ExecuteNonQuery();
    });

    private static TrackedSession ReadSession(SqliteDataReader reader) => new()
    {
        Id = reader.GetString(0),
        ProgramKey = reader.GetString(1),
        ProgramName = reader.GetString(2),
        ActivityKey = reader.GetString(3),
        StartAtMs = reader.GetInt64(4),
        EndAtMs = reader.IsDBNull(5) ? null : reader.GetInt64(5),
        LastSeenAtMs = reader.GetInt64(6),
        TimezoneId = reader.GetString(7),
        CreatedAtMs = reader.GetInt64(8),
    };

    // ------------------------------------------------------------------- Rules

    public void UpsertRule(TrackingRule rule) => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            INSERT INTO rules (program_key, program_name, activity_key, threshold_seconds, record_mode, enabled, created_at, updated_at)
            VALUES ($pk, $pn, $ak, $th, $rm, $en, $ca, $ua)
            ON CONFLICT(program_key) DO UPDATE SET
              program_name = excluded.program_name,
              activity_key = excluded.activity_key,
              threshold_seconds = excluded.threshold_seconds,
              record_mode = excluded.record_mode,
              enabled = excluded.enabled,
              updated_at = excluded.updated_at
            """;
        cmd.Parameters.AddWithValue("$pk", rule.ProgramKey);
        cmd.Parameters.AddWithValue("$pn", rule.ProgramName);
        cmd.Parameters.AddWithValue("$ak", rule.ActivityKey);
        cmd.Parameters.AddWithValue("$th", rule.ThresholdSeconds);
        cmd.Parameters.AddWithValue("$rm", (int)rule.Mode);
        cmd.Parameters.AddWithValue("$en", rule.Enabled ? 1 : 0);
        cmd.Parameters.AddWithValue("$ca", rule.CreatedAtMs);
        cmd.Parameters.AddWithValue("$ua", rule.UpdatedAtMs);
        cmd.ExecuteNonQuery();
        return 0;
    });

    public void DeleteRule(string programKey) => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "DELETE FROM rules WHERE program_key = $pk";
        cmd.Parameters.AddWithValue("$pk", programKey);
        cmd.ExecuteNonQuery();
        return 0;
    });

    public List<TrackingRule> GetRules() => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            SELECT program_key, program_name, activity_key, threshold_seconds, record_mode, enabled, created_at, updated_at
            FROM rules ORDER BY program_name COLLATE NOCASE
            """;
        using var reader = cmd.ExecuteReader();
        var list = new List<TrackingRule>();
        while (reader.Read())
        {
            list.Add(new TrackingRule
            {
                ProgramKey = reader.GetString(0),
                ProgramName = reader.GetString(1),
                ActivityKey = reader.GetString(2),
                ThresholdSeconds = reader.GetInt32(3),
                Mode = (RecordMode)reader.GetInt32(4),
                Enabled = reader.GetInt32(5) != 0,
                CreatedAtMs = reader.GetInt64(6),
                UpdatedAtMs = reader.GetInt64(7),
            });
        }
        return list;
    });

    // ------------------------------------------------------- Synced activities

    /// <summary>
    /// Ersetzt die vom Handy synchronisierten Aktivitäten (kompletter Abgleich —
    /// die App schickt bei jedem Sync ihren aktuellen Stand).
    /// </summary>
    public void ReplaceSyncedActivities(IReadOnlyList<SyncedActivity> activities) => WithDb(conn =>
    {
        using var tx = conn.BeginTransaction();
        using (var del = conn.CreateCommand())
        {
            del.Transaction = tx;
            del.CommandText = "DELETE FROM synced_activities;";
            del.ExecuteNonQuery();
        }
        foreach (var activity in activities)
        {
            using var cmd = conn.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = """
                INSERT INTO synced_activities (key, name, icon, category_key, updated_at)
                VALUES ($k, $n, $i, $c, $u)
                """;
            cmd.Parameters.AddWithValue("$k", activity.Key);
            cmd.Parameters.AddWithValue("$n", activity.Name);
            cmd.Parameters.AddWithValue("$i", activity.Icon);
            cmd.Parameters.AddWithValue("$c", activity.CategoryKey);
            cmd.Parameters.AddWithValue("$u", activity.UpdatedAtMs);
            cmd.ExecuteNonQuery();
        }
        tx.Commit();
        return 0;
    });

    public List<SyncedActivity> GetSyncedActivities() => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT key, name, icon, category_key, updated_at FROM synced_activities ORDER BY name COLLATE NOCASE";
        using var reader = cmd.ExecuteReader();
        var list = new List<SyncedActivity>();
        while (reader.Read())
        {
            list.Add(new SyncedActivity
            {
                Key = reader.GetString(0),
                Name = reader.GetString(1),
                Icon = reader.GetString(2),
                CategoryKey = reader.GetString(3),
                UpdatedAtMs = reader.GetInt64(4),
            });
        }
        return list;
    });

    // ---------------------------------------------------------------- Programs

    public void UpsertProgramSeen(string programKey, string name, string path, long nowMs, bool hidden) => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            INSERT INTO programs (program_key, name, path, first_seen_at, last_seen_at, hidden)
            VALUES ($pk, $n, $p, $now, $now, $h)
            ON CONFLICT(program_key) DO UPDATE SET
              name = excluded.name,
              path = CASE WHEN excluded.path <> '' THEN excluded.path ELSE programs.path END,
              last_seen_at = excluded.last_seen_at
            """;
        cmd.Parameters.AddWithValue("$pk", programKey);
        cmd.Parameters.AddWithValue("$n", name);
        cmd.Parameters.AddWithValue("$p", path);
        cmd.Parameters.AddWithValue("$now", nowMs);
        cmd.Parameters.AddWithValue("$h", hidden ? 1 : 0);
        cmd.ExecuteNonQuery();
        return 0;
    });

    public List<ProgramEntry> GetPrograms() => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            SELECT program_key, name, path, first_seen_at, last_seen_at, hidden
            FROM programs ORDER BY last_seen_at DESC
            """;
        using var reader = cmd.ExecuteReader();
        var list = new List<ProgramEntry>();
        while (reader.Read())
        {
            list.Add(new ProgramEntry
            {
                ProgramKey = reader.GetString(0),
                Name = reader.GetString(1),
                Path = reader.GetString(2),
                FirstSeenAtMs = reader.GetInt64(3),
                LastSeenAtMs = reader.GetInt64(4),
                Hidden = reader.GetInt32(5) != 0,
            });
        }
        return list;
    });

    // ---------------------------------------------------------------- Settings

    public string? GetSetting(string key) => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT value FROM settings WHERE key = $k";
        cmd.Parameters.AddWithValue("$k", key);
        return cmd.ExecuteScalar() as string;
    });

    public void SetSetting(string key, string value) => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            INSERT INTO settings (key, value) VALUES ($k, $v)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """;
        cmd.Parameters.AddWithValue("$k", key);
        cmd.Parameters.AddWithValue("$v", value);
        cmd.ExecuteNonQuery();
        return 0;
    });

    // ------------------------------------------------------------------ Intern

    private void Exec(string sql) => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
        return 0;
    });

    /// <summary>Existiert die Spalte bereits? (Für idempotente Migrationen.)</summary>
    private bool ColumnExists(string table, string column) => WithDb(conn =>
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = $"PRAGMA table_info({table});";
        using var reader = cmd.ExecuteReader();
        while (reader.Read())
        {
            if (string.Equals(reader.GetString(1), column, StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
        }
        return false;
    });

    private T WithDb<T>(Func<SqliteConnection, T> work)
    {
        _gate.Wait();
        try
        {
            if (_conn is null)
            {
                throw new InvalidOperationException("SessionStore ist nicht geöffnet (Open() fehlt).");
            }
            return work(_conn);
        }
        finally
        {
            _gate.Release();
        }
    }

    public void Dispose()
    {
        _gate.Wait();
        try
        {
            _conn?.Dispose();
            _conn = null;
        }
        finally
        {
            _gate.Release();
        }
    }
}
