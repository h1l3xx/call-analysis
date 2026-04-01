export function useFormatters() {
  function formatDate(ts: number | null | undefined): string {
    if (!ts) return '—'
    return new Date(ts).toLocaleDateString('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  function formatDuration(seconds: number | null | undefined): string {
    if (!seconds) return '—'
    const m = Math.floor(seconds / 60)
    const s = seconds % 60
    return `${m}:${String(s).padStart(2, '0')}`
  }

  function formatScore(score: number | null | undefined): string {
    if (score == null) return '—'
    return score.toFixed(1)
  }

  function formatPercent(ratio: number | null | undefined): string {
    if (ratio == null) return '—'
    return `${(ratio * 100).toFixed(0)}%`
  }

  return { formatDate, formatDuration, formatScore, formatPercent }
}
