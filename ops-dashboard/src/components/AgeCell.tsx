const HOUR_MS = 60 * 60 * 1000;

function formatAge(ms: number): string {
  if (ms < 0) return "0m";
  const totalMinutes = Math.floor(ms / 60000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return `${minutes}m`;
  if (hours < 24) return `${hours}h ${minutes}m`;
  const days = Math.floor(hours / 24);
  return `${days}d ${hours % 24}h`;
}

function ageClass(ms: number): string {
  if (ms >= 4 * HOUR_MS) return "age-hot";
  if (ms >= 1 * HOUR_MS) return "age-warm";
  return "age-fresh";
}

/**
 * "A case open for four hours is a different object from one opened ninety
 * seconds ago" (brief §3) -- so age is not just a duration string, it is a
 * three-step color signal (fresh / warm / over four hours) that reads
 * before the minutes do. The thresholds are a judgement call with nothing
 * in the API or brief to anchor them; see PHASE-9-REPORT.md §5.
 */
export function AgeCell({ openedAt, now }: { openedAt: string; now: number }) {
  const ms = now - new Date(openedAt).getTime();
  return (
    <span className={`age ${ageClass(ms)}`} title={new Date(openedAt).toLocaleString()}>
      {formatAge(ms)}
    </span>
  );
}
