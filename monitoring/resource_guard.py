"""Provisional operator limits for a 1-VU read experiment, not a service SLO."""


def stop_reason(previous, current):
    if current['availableKiB'] < 100 * 1024:
        return 'Available memory below 100MiB'
    if current['swapOutPages'] > previous['swapOutPages']:
        return 'New swap-out observed'
    elapsed = current['at'] - previous['at']
    if elapsed <= 0:
        return 'Invalid resource sample interval'
    read_bytes = max(0, current['swapInPages'] - previous['swapInPages']) * current['pageBytes']
    if read_bytes / elapsed >= 1024 * 1024:
        return 'Swap-in rate reached 1MiB/s'
    if current['memorySomeAvg10'] >= 1:
        return 'Memory PSI some avg10 reached 1 percent'
    return None
