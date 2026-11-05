"""Provisional operator limits for a 1-VU read experiment, not a service SLO."""


def stop_reason(previous, current):
    if current['availableKiB'] < 100 * 1024:
        return 'Available memory below 100MiB'
    elapsed = current['at'] - previous['at']
    if elapsed <= 0:
        return 'Invalid resource sample interval'
    # 2026-11-04: 운영 서버는 평소에도 소량 swap-out이 있어 '1페이지라도'는 너무 엄격했다(issue-log #6).
    out_bytes = max(0, current['swapOutPages'] - previous['swapOutPages']) * current['pageBytes']
    if out_bytes / elapsed >= 1024 * 1024:
        return 'Swap-out rate reached 1MiB/s'
    read_bytes = max(0, current['swapInPages'] - previous['swapInPages']) * current['pageBytes']
    if read_bytes / elapsed >= 1024 * 1024:
        return 'Swap-in rate reached 1MiB/s'
    if current['memorySomeAvg10'] >= 1:
        return 'Memory PSI some avg10 reached 1 percent'
    return None
