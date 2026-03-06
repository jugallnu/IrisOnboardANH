using System.Threading.RateLimiting;

namespace DeviceRegistration;

/// <summary>
/// Per-key fixed-window rate limiter for device registration requests.
/// Keyed by userId so each user has an independent limit.
/// Note: this is in-memory and per-instance. For multi-instance deployments
/// consider a distributed backend (e.g. Azure Cache for Redis).
/// </summary>
public sealed class RateLimiterService : IDisposable
{
    private readonly PartitionedRateLimiter<string> _limiter;

    // Configurable via constructor — defaults: 5 requests per minute per user
    public RateLimiterService(int permitLimit = 5, int windowSeconds = 60)
    {
        _limiter = PartitionedRateLimiter.Create<string, string>(key =>
            RateLimitPartition.GetFixedWindowLimiter(key, _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit          = permitLimit,
                Window               = TimeSpan.FromSeconds(windowSeconds),
                QueueProcessingOrder = QueueProcessingOrder.OldestFirst,
                QueueLimit           = 0    // reject immediately, no queuing
            }));
    }

    /// <summary>
    /// Returns true if the request for the given key is within the rate limit.
    /// </summary>
    public bool IsAllowed(string key)
    {
        using var lease = _limiter.AttemptAcquire(key);
        return lease.IsAcquired;
    }

    public void Dispose() => _limiter.Dispose();
}
