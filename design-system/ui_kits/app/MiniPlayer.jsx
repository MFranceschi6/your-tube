// MiniPlayer.jsx — YourTube UI Kit
// Persistent bottom-docked player above tab bar.

const MiniPlayer = ({ track, isPlaying, progress = 0, onTogglePlay, onExpand, onNext, onPrev }) => {
  if (!track) return null;

  return (
    <div style={{ padding: '0 8px 8px' }}>
      <div
        style={{
          display: 'flex', alignItems: 'center', gap: 10,
          padding: '8px 10px 10px',
          background: '#1C1C1E',
          borderRadius: 16,
          boxShadow: '0 4px 24px rgba(0,0,0,0.5)',
          border: '1px solid #3A3A3C',
          cursor: 'pointer',
          position: 'relative',
          overflow: 'hidden',
        }}
        onClick={onExpand}
      >
        {/* Artwork */}
        <div style={{ width: 48, height: 48, borderRadius: 8, flexShrink: 0, background: isPlaying ? 'rgba(139,92,246,0.2)' : '#2C2C2E', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' }}>
          {track.thumbnailUrl ? (
            <img src={track.thumbnailUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          ) : (
            <svg width="18" height="18" viewBox="0 0 24 24" fill={isPlaying ? '#8B5CF6' : 'rgba(235,235,245,0.3)'} ><polygon points="5,3 19,12 5,21"/></svg>
          )}
        </div>

        {/* Info */}
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 14, fontWeight: 500, color: '#FFFFFF', fontFamily: 'var(--font-sans)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {track.title}
          </div>
          <div style={{ fontSize: 12, color: 'rgba(235,235,245,0.6)', fontFamily: 'var(--font-sans)' }}>
            {track.channel}
          </div>
        </div>

        {/* Controls */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 2, flexShrink: 0 }} onClick={e => e.stopPropagation()}>
          <MpBtn onClick={onPrev} label="Previous">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polygon points="19,20 9,12 19,4"/><line x1="5" y1="19" x2="5" y2="5"/></svg>
          </MpBtn>
          <MpBtn onClick={onTogglePlay} label={isPlaying ? 'Pause' : 'Play'} accent>
            {isPlaying
              ? <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg>
              : <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><polygon points="5,3 19,12 5,21"/></svg>
            }
          </MpBtn>
          <MpBtn onClick={onNext} label="Next">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polygon points="5,4 15,12 5,20"/><line x1="19" y1="5" x2="19" y2="19"/></svg>
          </MpBtn>
        </div>

        {/* Progress bar at bottom */}
        <div style={{ position: 'absolute', bottom: 0, left: 0, right: 0, height: 2, background: '#3A3A3C' }}>
          <div style={{ height: '100%', background: '#8B5CF6', width: `${progress * 100}%`, transition: 'width 1s linear', borderRadius: '0 1px 1px 0' }} />
        </div>
      </div>
    </div>
  );
};

const MpBtn = ({ onClick, label, accent, children }) => (
  <button
    onClick={onClick}
    aria-label={label}
    style={{
      width: 40, height: 40, display: 'flex', alignItems: 'center', justifyContent: 'center',
      borderRadius: '50%', background: 'transparent', border: 'none', cursor: 'pointer',
      color: accent ? '#8B5CF6' : '#FFFFFF', transition: 'background 150ms ease',
    }}
    onMouseEnter={e => e.currentTarget.style.background = '#2C2C2E'}
    onMouseLeave={e => e.currentTarget.style.background = 'transparent'}
  >
    {children}
  </button>
);

Object.assign(window, { MiniPlayer });
