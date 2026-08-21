/** A deliberate message, not a bare empty table -- so "no cases" reads as fact, not as the screen having failed to load. */
export function EmptyState({ title, detail }: { title: string; detail?: string }) {
  return (
    <div className="empty-state">
      <div className="empty-title">{title}</div>
      {detail && <div>{detail}</div>}
    </div>
  );
}
