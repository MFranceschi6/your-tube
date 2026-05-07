// SearchScreen.jsx — YourTube UI Kit

const SAMPLE_RESULTS = [
  { videoId: 'r1', title: 'lofi hip hop radio – beats to relax/study to', channel: 'Lofi Girl', durationSec: 0 },
  { videoId: 'r2', title: 'Chill Lofi Mix – Deep Focus 2024', channel: 'ChillHop Music', durationSec: 3612 },
  { videoId: 'r3', title: 'Jazz & Bossa Nova – Morning Coffee', channel: 'Café Music BGM', durationSec: 3847 },
  { videoId: 'r4', title: 'Peaceful Piano – Study & Work', channel: 'Soothing Relaxation', durationSec: 7204 },
  { videoId: 'r5', title: 'Lo-Fi Beats Vol. 3 – Rain Sounds', channel: 'Lofi Records', durationSec: 5400 },
];

const SUGGESTIONS = ['lofi beats', 'jazz cafe', 'bohemian rhapsody', 'classical focus'];

const SearchScreen = ({ currentTrack, onPlayTrack }) => {
  const [query, setQuery] = React.useState('');
  const [focused, setFocused] = React.useState(false);
  const [loading, setLoading] = React.useState(false);
  const [results, setResults] = React.useState([]);
  const [searched, setSearched] = React.useState(false);
  const inputRef = React.useRef(null);

  const handleSearch = (q) => {
    if (!q.trim()) return;
    setLoading(true);
    setSearched(true);
    setTimeout(() => {
      setResults(SAMPLE_RESULTS.filter(r => r.title.toLowerCase().includes(q.toLowerCase()) || r.channel.toLowerCase().includes(q.toLowerCase()) || true));
      setLoading(false);
    }, 800);
  };

  const handleSuggestion = (s) => {
    setQuery(s);
    handleSearch(s);
    inputRef.current?.blur();
    setFocused(false);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', background: '#0F0F0F' }}>
      {/* Search header */}
      <div style={{ padding: '12px 16px 8px', background: '#0F0F0F', borderBottom: searched ? '1px solid #3A3A3C' : 'none' }}>
        <div style={{ fontSize: 24, fontWeight: 600, color: '#FFF', fontFamily: 'var(--font-sans)', letterSpacing: '-0.02em', marginBottom: 10 }}>Search</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{
            flex: 1, display: 'flex', alignItems: 'center', gap: 10,
            height: 44, padding: '0 14px',
            background: focused ? '#1C1C1E' : '#2C2C2E',
            borderRadius: 999,
            border: `1.5px solid ${focused ? '#8B5CF6' : 'transparent'}`,
            transition: 'all 200ms ease',
          }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke={focused ? '#8B5CF6' : 'rgba(235,235,245,0.4)'} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0 }}><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
            <input
              ref={inputRef}
              value={query}
              onChange={e => setQuery(e.target.value)}
              onFocus={() => setFocused(true)}
              onBlur={() => setFocused(false)}
              onKeyDown={e => e.key === 'Enter' && handleSearch(query)}
              placeholder="Search"
              style={{ flex: 1, background: 'none', border: 'none', outline: 'none', fontFamily: 'var(--font-sans)', fontSize: 15, color: '#FFF' }}
            />
            {query ? (
              <button onClick={() => { setQuery(''); setResults([]); setSearched(false); }} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'rgba(235,235,245,0.4)', display: 'flex', padding: 0 }}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>
              </button>
            ) : null}
          </div>
          {focused && <button onClick={() => { setFocused(false); inputRef.current?.blur(); }} style={{ background: 'none', border: 'none', cursor: 'pointer', fontFamily: 'var(--font-sans)', fontSize: 15, fontWeight: 500, color: '#8B5CF6', flexShrink: 0 }}>Cancel</button>}
          {!focused && query && <button onClick={() => handleSearch(query)} style={{ background: '#8B5CF6', border: 'none', cursor: 'pointer', fontFamily: 'var(--font-sans)', fontSize: 14, fontWeight: 600, color: '#FFF', borderRadius: 999, padding: '0 16px', height: 36 }}>Search</button>}
        </div>
      </div>

      {/* Body */}
      <div style={{ flex: 1, overflowY: 'auto' }}>
        {!searched && !focused && (
          <div style={{ padding: '20px 16px' }}>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'rgba(235,235,245,0.4)', letterSpacing: '0.06em', textTransform: 'uppercase', marginBottom: 12 }}>Suggestions</div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {SUGGESTIONS.map(s => (
                <button key={s} onClick={() => handleSuggestion(s)} style={{ padding: '8px 16px', background: '#1C1C1E', border: '1px solid #3A3A3C', borderRadius: 999, fontFamily: 'var(--font-sans)', fontSize: 14, color: '#FFF', cursor: 'pointer', transition: 'background 150ms ease' }}
                  onMouseEnter={e => e.currentTarget.style.background = '#2C2C2E'}
                  onMouseLeave={e => e.currentTarget.style.background = '#1C1C1E'}
                >{s}</button>
              ))}
            </div>
          </div>
        )}

        {loading && (
          <div style={{ padding: '8px 0' }}>
            {[1,2,3,4].map(i => <TrackRowSkeleton key={i} />)}
          </div>
        )}

        {!loading && searched && results.length === 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, padding: '48px 32px', textAlign: 'center' }}>
            <div style={{ width: 52, height: 52, background: '#1C1C1E', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="rgba(235,235,245,0.3)" strokeWidth="1.5" strokeLinecap="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
            </div>
            <div style={{ fontSize: 16, fontWeight: 600, color: '#FFF', fontFamily: 'var(--font-sans)' }}>No results found</div>
            <div style={{ fontSize: 14, color: 'rgba(235,235,245,0.5)', fontFamily: 'var(--font-sans)' }}>Try a different search</div>
          </div>
        )}

        {!loading && results.length > 0 && (
          <div style={{ paddingBottom: 8 }}>
            {results.map((track, i) => (
              <TrackRow
                key={track.videoId}
                track={track}
                isPlaying={currentTrack?.videoId === track.videoId}
                onPlay={() => onPlayTrack(track)}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

Object.assign(window, { SearchScreen });
