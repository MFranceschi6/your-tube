// NowPlaying.jsx — YourTube UI Kit
// Full-screen player overlay

const NowPlaying = ({ track, isPlaying, progress = 0, onTogglePlay, onClose, onNext, onPrev, queue = [] }) => {
  const [showQueue, setShowQueue] = React.useState(false);
  const formatDuration = (sec) => {
    if (!sec) return '0:00';
    const m = Math.floor(sec / 60);
    const s = String(sec % 60).padStart(2, '0');
    return `${m}:${s}`;
  };
  const elapsed = formatDuration(Math.floor((track?.durationSec || 0) * progress));
  const total = formatDuration(track?.durationSec || 0);

  if (!track) return null;

  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 50,
      background: 'linear-gradient(160deg, #2C1B4E 0%, #141414 55%, #0F0F0F 100%)',
      display: 'flex', flexDirection: 'column',
      fontFamily: 'var(--font-sans)',
      overflowY: 'auto',
    }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px 8px' }}>
        <button onClick={onClose} style={iconBtnStyle} aria-label="Close player">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><polyline points="6,9 12,15 18,9"/></svg>
        </button>
        <span style={{ fontSize: 13, fontWeight: 600, color: 'rgba(235,235,245,0.6)', letterSpacing: '0.06em', textTransform: 'uppercase' }}>Now Playing</span>
        <button style={iconBtnStyle} aria-label="More options">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><circle cx="12" cy="5" r="1" fill="currentColor"/><circle cx="12" cy="12" r="1" fill="currentColor"/><circle cx="12" cy="19" r="1" fill="currentColor"/></svg>
        </button>
      </div>

      {/* Artwork */}
      <div style={{ padding: '12px 32px 0', flex: showQueue ? 0 : 1, display: 'flex', alignItems: 'center' }}>
        <div style={{
          width: '100%', aspectRatio: '1', borderRadius: 16,
          background: 'linear-gradient(135deg, #3D1F7A 0%, #1F3A7A 100%)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: '0 16px 48px rgba(0,0,0,0.7)',
          overflow: 'hidden',
          transform: isPlaying ? 'scale(1)' : 'scale(0.92)',
          transition: 'transform 300ms ease',
        }}>
          {track.thumbnailUrl
            ? <img src={track.thumbnailUrl} alt={track.title} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
            : <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="rgba(255,255,255,0.2)" strokeWidth="1"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>
          }
        </div>
      </div>

      {/* Meta + scrubber + controls */}
      <div style={{ padding: '20px 24px 24px', display: 'flex', flexDirection: 'column', gap: 18 }}>
        {/* Track info */}
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 22, fontWeight: 600, color: '#FFFFFF', letterSpacing: '-0.02em', lineHeight: 1.2, marginBottom: 4 }}>{track.title}</div>
            <div style={{ fontSize: 15, color: 'rgba(235,235,245,0.6)' }}>{track.channel}</div>
          </div>
          <button style={{ ...iconBtnStyle, color: 'rgba(235,235,245,0.4)', flexShrink: 0, marginTop: 2 }} aria-label="Add to playlist">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          </button>
        </div>

        {/* Scrubber */}
        <div>
          <div style={{ height: 4, background: '#3A3A3C', borderRadius: 2, marginBottom: 8, position: 'relative', cursor: 'pointer' }}>
            <div style={{ height: '100%', background: '#8B5CF6', width: `${progress * 100}%`, borderRadius: 2, position: 'relative' }}>
              <div style={{ position: 'absolute', right: -7, top: -6, width: 16, height: 16, background: 'white', borderRadius: '50%', boxShadow: '0 2px 8px rgba(0,0,0,0.4)' }} />
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'rgba(235,235,245,0.4)', fontVariantNumeric: 'tabular-nums' }}>
            <span>{elapsed}</span><span>{total}</span>
          </div>
        </div>

        {/* Transport controls */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <NpBtn label="Shuffle" small>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="16,3 21,3 21,8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21,16 21,21 16,21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>
          </NpBtn>
          <NpBtn onClick={onPrev} label="Previous">
            <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polygon points="19,20 9,12 19,4"/><line x1="5" y1="19" x2="5" y2="5"/></svg>
          </NpBtn>
          <button
            onClick={onTogglePlay} aria-label={isPlaying ? 'Pause' : 'Play'}
            style={{ width: 64, height: 64, borderRadius: '50%', background: '#8B5CF6', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'white', boxShadow: '0 4px 20px rgba(139,92,246,0.45)', transition: 'background 150ms ease, transform 100ms ease', transform: 'scale(1)' }}
            onMouseEnter={e => { e.currentTarget.style.background = '#7C3AED'; e.currentTarget.style.transform = 'scale(1.04)'; }}
            onMouseLeave={e => { e.currentTarget.style.background = '#8B5CF6'; e.currentTarget.style.transform = 'scale(1)'; }}
          >
            {isPlaying
              ? <svg width="28" height="28" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg>
              : <svg width="28" height="28" viewBox="0 0 24 24" fill="currentColor" style={{ marginLeft: 3 }}><polygon points="5,3 19,12 5,21"/></svg>
            }
          </button>
          <NpBtn onClick={onNext} label="Next">
            <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polygon points="5,4 15,12 5,20"/><line x1="19" y1="5" x2="19" y2="19"/></svg>
          </NpBtn>
          <NpBtn label="Queue" small onClick={() => setShowQueue(q => !q)} active={showQueue}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
          </NpBtn>
        </div>

        {/* Queue (collapsible) */}
        {showQueue && queue.length > 0 && (
          <div style={{ background: 'rgba(28,28,30,0.7)', borderRadius: 12, overflow: 'hidden' }}>
            <div style={{ padding: '10px 16px', fontSize: 12, fontWeight: 600, color: 'rgba(235,235,245,0.4)', textTransform: 'uppercase', letterSpacing: '0.08em' }}>Up Next</div>
            {queue.map((t, i) => (
              <div key={t.videoId} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 16px', borderTop: '1px solid #3A3A3C' }}>
                <div style={{ width: 40, height: 40, borderRadius: 6, background: '#2C2C2E', flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="rgba(235,235,245,0.25)"><polygon points="5,3 19,12 5,21"/></svg>
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 500, color: '#FFF', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{t.title}</div>
                  <div style={{ fontSize: 12, color: 'rgba(235,235,245,0.4)' }}>{t.channel}</div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

const iconBtnStyle = { width: 40, height: 40, display: 'flex', alignItems: 'center', justifyContent: 'center', borderRadius: '50%', background: 'transparent', border: 'none', cursor: 'pointer', color: 'rgba(235,235,245,0.8)', transition: 'background 150ms ease' };

const NpBtn = ({ onClick, label, children, small, active }) => (
  <button onClick={onClick} aria-label={label} style={{
    width: small ? 40 : 52, height: small ? 40 : 52,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    borderRadius: '50%', background: active ? 'rgba(139,92,246,0.2)' : 'transparent',
    border: 'none', cursor: 'pointer',
    color: active ? '#8B5CF6' : 'rgba(235,235,245,0.8)',
    transition: 'background 150ms ease',
  }}
    onMouseEnter={e => e.currentTarget.style.background = active ? 'rgba(139,92,246,0.3)' : 'rgba(255,255,255,0.08)'}
    onMouseLeave={e => e.currentTarget.style.background = active ? 'rgba(139,92,246,0.2)' : 'transparent'}
  >
    {children}
  </button>
);

Object.assign(window, { NowPlaying });
