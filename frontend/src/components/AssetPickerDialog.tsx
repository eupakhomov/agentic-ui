import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/rest';
import type { AssetKind, LibraryAsset } from '../protocol';

const PAGE_SIZE = 40;
const SEMANTIC_K = 100;

/** Browse-to-add picker for library skills/agents: filter, infinite scroll, bulk select. */
export default function AssetPickerDialog({
  kind,
  semanticAvailable,
  selected,
  onConfirm,
  onClose,
}: {
  kind: AssetKind;
  semanticAvailable: boolean;
  selected: Map<string, LibraryAsset>;
  onConfirm: (picked: Map<string, LibraryAsset>) => void;
  onClose: () => void;
}) {
  const label = kind === 'skill' ? 'skills' : 'agents';
  const [textFilter, setTextFilter] = useState('');
  const [query, setQuery] = useState('');
  const [semantic, setSemantic] = useState(false);
  const [searched, setSearched] = useState(false);
  const [assets, setAssets] = useState<LibraryAsset[]>([]);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [picked, setPicked] = useState<Map<string, LibraryAsset>>(() => new Map(selected));

  const fetchingRef = useRef(false);
  const stateRef = useRef({ assets, hasMore, query });
  stateRef.current = { assets, hasMore, query };

  const loadMore = useCallback((reset: boolean) => {
    if (fetchingRef.current) return;
    if (!reset && !stateRef.current.hasMore) return;
    fetchingRef.current = true;
    setLoading(true);
    const offset = reset ? 0 : stateRef.current.assets.length;
    api.libraryAssets({ kind, status: 'ACTIVE', q: stateRef.current.query, limit: PAGE_SIZE, offset })
      .then((page) => {
        setAssets((prev) => (reset ? page : [...prev, ...page]));
        setHasMore(page.length === PAGE_SIZE);
        setError('');
      })
      .catch((e) => setError(String((e as Error).message ?? e)))
      .finally(() => { setLoading(false); fetchingRef.current = false; });
  }, [kind]);

  // debounce the free-text filter
  useEffect(() => {
    if (semantic) return;
    const t = setTimeout(() => setQuery(textFilter.trim()), 300);
    return () => clearTimeout(t);
  }, [textFilter, semantic]);

  // (re)load from scratch whenever the non-semantic query changes, and on mount
  useEffect(() => {
    if (semantic) return;
    setAssets([]);
    setHasMore(true);
    loadMore(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, semantic]);

  const runSemanticSearch = () => {
    if (!textFilter.trim()) return;
    setLoading(true);
    setSearched(true);
    api.librarySearch(textFilter.trim(), SEMANTIC_K, kind)
      .then((hits) => { setAssets(hits.map((h) => h.asset)); setHasMore(false); setError(''); })
      .catch((e) => setError(String((e as Error).message ?? e)))
      .finally(() => setLoading(false));
  };

  const sentinelRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = sentinelRef.current;
    if (!el) return;
    const obs = new IntersectionObserver((entries) => {
      if (entries[0]?.isIntersecting && !semantic) loadMore(false);
    }, { root: el.parentElement, rootMargin: '80px' });
    obs.observe(el);
    return () => obs.disconnect();
  }, [loadMore, semantic]);

  const toggle = (a: LibraryAsset) => {
    setPicked((prev) => {
      const next = new Map(prev);
      if (next.has(a.id)) next.delete(a.id); else next.set(a.id, a);
      return next;
    });
  };

  const allLoadedSelected = assets.length > 0 && assets.every((a) => picked.has(a.id));
  const toggleAllLoaded = () => {
    setPicked((prev) => {
      const next = new Map(prev);
      if (allLoadedSelected) { for (const a of assets) next.delete(a.id); }
      else { for (const a of assets) next.set(a.id, a); }
      return next;
    });
  };

  const awaitingSemanticSearch = semantic && !searched;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()}>
        <h2>Add {label}</h2>

        <div className="usage-filters">
          <input
            style={{ flex: 1 }}
            autoFocus
            placeholder={semantic ? 'semantic search — press Enter' : `filter ${label} by name, description, or tag`}
            value={textFilter}
            onChange={(e) => setTextFilter(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && semantic) runSemanticSearch(); }}
          />
          {semanticAvailable && (
            <label style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--muted)', whiteSpace: 'nowrap' }}>
              <input
                type="checkbox"
                checked={semantic}
                onChange={(e) => {
                  const on = e.target.checked;
                  setSemantic(on);
                  setSearched(false);
                  if (on) { setAssets([]); setHasMore(false); }
                }}
              />
              semantic
            </label>
          )}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '0 0 8px' }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--muted)' }}>
            <input type="checkbox" checked={allLoadedSelected} disabled={assets.length === 0} onChange={toggleAllLoaded} />
            select all loaded ({assets.filter((a) => picked.has(a.id)).length}/{assets.length})
          </label>
          <span style={{ marginLeft: 'auto', color: 'var(--muted)', fontSize: 'calc(12.5px * var(--font-scale))' }}>
            {picked.size} selected
          </span>
        </div>

        {error && <div className="error-text" style={{ marginBottom: 8 }}>{error}</div>}
        {awaitingSemanticSearch && <div style={{ color: 'var(--muted)' }}>press Enter to search</div>}

        <div className="candidate-list">
          {!awaitingSemanticSearch && assets.map((a) => (
            <div
              key={a.id}
              className="candidate-row"
              style={{ cursor: 'pointer' }}
              onClick={() => toggle(a)}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <input
                  type="checkbox"
                  checked={picked.has(a.id)}
                  onChange={() => toggle(a)}
                  onClick={(e) => e.stopPropagation()}
                />
                <span className="name">{a.name}</span>
                <span
                  style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: 'var(--muted)' }}
                  title={a.description}
                >
                  {a.description}
                </span>
                {a.tags.map((t) => <span key={t} className="chip">{t}</span>)}
              </div>
            </div>
          ))}
          {!awaitingSemanticSearch && !loading && assets.length === 0 && (
            <div style={{ color: 'var(--muted)' }}>no {label} found</div>
          )}
          {!semantic && <div ref={sentinelRef} style={{ height: 1 }} />}
          {loading && <div style={{ color: 'var(--muted)', textAlign: 'center', padding: 6 }}>loading…</div>}
        </div>

        <div className="actions">
          <button onClick={onClose}>Cancel</button>
          <button className="primary" onClick={() => onConfirm(picked)}>
            Use {picked.size} selected
          </button>
        </div>
      </div>
    </div>
  );
}
