using System.Security.Cryptography;

namespace Aevum.PcTracker.Core.Services;

public enum PairOutcome
{
    Success,
    Invalid,
    Locked,
    NotActive,
}

public sealed record PairResult(PairOutcome Outcome, int RetryAfterSeconds = 0);

/// <summary>
/// Der Kopplungs-Code: 6 Ziffern, gilt nur solange der Kopplungsmodus aktiv ist,
/// rotiert nach jeder erfolgreichen Kopplung. Fehlversuche sind rate-limitiert
/// (max. <see cref="MaxFailures"/> in <see cref="FailureWindowMs"/>, dann Sperre).
/// Reine Logik mit injizierter Zeit — einzeln testbar.
/// </summary>
public sealed class PairingManager
{
    public const int MaxFailures = 5;
    public const long FailureWindowMs = 5 * 60_000;
    public const int LockDurationSeconds = 120;

    private readonly object _gate = new();
    private bool _enabled;
    private string _code = "";
    private readonly Queue<long> _failures = new();
    private long _lockedUntilMs;

    /// <summary>Kopplungsmodus aktiv? (Kopplungs-Tab offen / Schalter an)</summary>
    public bool Enabled
    {
        get { lock (_gate) { return _enabled; } }
    }

    /// <summary>Aktueller Code (nur für die PC-Oberfläche — nie über die API, außer als Prüfung).</summary>
    public string CurrentCode
    {
        get { lock (_gate) { return _code; } }
    }

    /// <summary>Aktiviert die Kopplung und erzeugt einen frischen Code.</summary>
    public string Enable()
    {
        lock (_gate)
        {
            _enabled = true;
            RotateCodeLocked();
            return _code;
        }
    }

    public void Disable()
    {
        lock (_gate)
        {
            _enabled = false;
            _code = "";
        }
    }

    public string RotateCode()
    {
        lock (_gate)
        {
            RotateCodeLocked();
            return _code;
        }
    }

    public PairResult TryPair(string? presentedCode, long nowMs)
    {
        lock (_gate)
        {
            if (!_enabled)
            {
                return new PairResult(PairOutcome.NotActive);
            }

            // Abgelaufene Sperre aufheben.
            if (_lockedUntilMs > 0 && nowMs >= _lockedUntilMs)
            {
                _lockedUntilMs = 0;
                _failures.Clear();
            }
            if (nowMs < _lockedUntilMs)
            {
                var retry = (int)Math.Ceiling((_lockedUntilMs - nowMs) / 1000.0);
                return new PairResult(PairOutcome.Locked, retry);
            }

            // Alte Fehlversuche aus dem Fenster werfen.
            while (_failures.Count > 0 && nowMs - _failures.Peek() > FailureWindowMs)
            {
                _failures.Dequeue();
            }

            var normalized = Normalize(presentedCode);
            if (normalized.Length != 6 || !FixedTimeEquals(normalized, _code))
            {
                _failures.Enqueue(nowMs);
                if (_failures.Count >= MaxFailures)
                {
                    _lockedUntilMs = nowMs + LockDurationSeconds * 1000L;
                    return new PairResult(PairOutcome.Locked, LockDurationSeconds);
                }
                return new PairResult(PairOutcome.Invalid);
            }

            // Erfolg: Code rotiert, Fehlversuche vergessen.
            _failures.Clear();
            RotateCodeLocked();
            return new PairResult(PairOutcome.Success);
        }
    }

    private void RotateCodeLocked()
    {
        // 6 Ziffern, kryptographisch zufällig (GetInt32 ist uniform, kein Modulo-Bias).
        var value = RandomNumberGenerator.GetInt32(0, 1_000_000);
        _code = value.ToString("D6");
    }

    /// <summary>Entfernt Leerzeichen/Bindestriche — Nutzer tippen den Code oft als „482 913" ab.</summary>
    public static string Normalize(string? code)
    {
        // Obergrenze: API-Eingaben dürfen nie den Stack sprengen (stackalloc).
        if (string.IsNullOrEmpty(code) || code.Length > 64)
        {
            return "";
        }
        Span<char> buffer = stackalloc char[code.Length];
        var n = 0;
        foreach (var c in code)
        {
            if (char.IsAsciiDigit(c))
            {
                buffer[n++] = c;
            }
        }
        return new string(buffer[..n]);
    }

    private static bool FixedTimeEquals(string a, string b)
    {
        if (a.Length != b.Length)
        {
            return false;
        }
        return CryptographicOperations.FixedTimeEquals(
            System.Text.Encoding.ASCII.GetBytes(a),
            System.Text.Encoding.ASCII.GetBytes(b));
    }
}
