// TrackRow.jsx — YourTube UI Kit
// A single track in a list. Thumbnail placeholder, title, channel, duration, overflow menu.

const TrackRow = ({ track, isPlaying = false, onPlay, onOverflow }) => {
  const { title, channel, durationSec, thumbnailUrl } = track;

  const formatDuration = (sec) => {
    if (!sec) return 'Live';
    const m = Math.floor(sec / 60);
    const s = String(sec % 60).padStart(2, '0');
    return `${m}:${s}`;
  };

  const trackRowStyles = {
    display: 'flex', alignItems: 'center', gap: 12,
    padding: '10px 16px', background: isPlaying ? '#2C2C2E' : '#1C1C1E',
    cursor: 'pointer', transition: 'background 200ms ease',
    minHeight: 72, borderTop: '1px solid #3A3A3C',
  };

  return (
    <div
      style={trackRowStyles}
      onClick={onPlay}
      onMouseEnter={e => { if (!isPlaying) e.currentTarget.style.background = '#2C2C2E'; }}
      onMouseLeave={e => { if (!isPlaying) e.currentTarget.style.background = '#1C1C1E'; }}
    >
      {/* Thumbnail */}
      <div style={{ width: 56, height: 56, borderRadius: 8, flexShrink: 0, background: isPlaying ? 'rgba(139,92,246,0.15)' : '#2C2C2E', overflow: 'hidden', display: 'flex', alignItems: 'center', justifyContent: 'center', position: 'relative' }}>
        {thumbnailUrl ? (
          <img src={thumbnailUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
        ) : isPlaying ? (
          <PlayingIndicator />
        ) : (
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="rgba(235,235,245,0.25)" strokeWidth="1.5"><polygon points="5,3 19,12 5,21"/></svg>
        )}
      </div>

      {/* Info */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 3 }}>
        <div style={{ fontSize: 15, fontWeight: 500, color: isPlaying ? '#8B5CF6' : '#FFFFFF', fontFamily: 'var(--font-sans)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {title}
        </div>
        <div style={{ fontSize: 13, color: 'rgba(235,235,245,0.6)', fontFamily: 'var(--font-sans)', display: 'flex', alignItems: 'center', gap: 6 }}>
          <span>{channel}</span>
          <span style={{ width: 2, height: 2, borderRadius: '50%', background: 'rgba(235,235,245,0.3)', flexShrink: 0, display: 'inline-block' }}></span>
          <span style={{ fontVariantNumeric: 'tabular-nums' }}>{formatDuration(durationSec)}</span>
        </div>
      </div>

      {/* Overflow */}
      <button
        onClick={e => { e.stopPropagation(); onOverflow && onOverflow(track); }}
        style={{ width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center', borderRadius: '50%', background: 'transparent', border: 'none', cursor: 'pointer', color: 'rgba(235,235,245,0.4)', flexShrink: 0, transition: 'background 150ms ease' }}
        onMouseEnter={e => e.currentTarget.style.background = '#2C2C2E'}
        onMouseLeave={e => e.currentTarget.style.background = 'transparent'}
        aria-label={`More options for ${title}`}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><circle cx="12" cy="5" r="1" fill="currentColor"/><circle cx="12" cy="12" r="1" fill="currentColor"/><circle cx="12" cy="19" r="1" fill="currentColor"/></svg>
      </button>
    </div>
  );
};

const PlayingIndicator = () => (
  <div style={{ display: 'flex', alignItems: 'flex-end', gap: 2, height: 16 }}>
    {[0, 0.2, 0.4].map((delay, i) => (
      <div key={i} style={{
        width: 3, background: '#8B5CF6', borderRadius: 2,
        animation: `eq-bounce 0.8s ease-in-out ${delay}s infinite alternate`,
      }} />
    ))}
    <style>{`@keyframes eq-bounce { from { height: 4px; } to { height: 16px; } }`}</style>
  </div>
);

const TrackRowSkeleton = () => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 16px', minHeight: 72, borderTop: '1px solid #3A3A3C' }}>
    <div style={{ width: 56, height: 56, borderRadius: 8, background: '#2C2C2E', flexShrink: 0, ...shimmerStyle }} />
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 8 }}>
      <div style={{ height: 14, width: '62%', background: '#2C2C2E', borderRadius: 4, ...shimmerStyle }} />
      <div style={{ height: 12, width: '38%', background: '#2C2C2E', borderRadius: 4, ...shimmerStyle }} />
    </div>
    <style>{`@keyframes shimmer { 0%{opacity:1} 50%{opacity:0.5} 100%{opacity:1} }`}</style>
  </div>
);

const shimmerStyle = { animation: 'shimmer 1.5s ease-in-out infinite' };

Object.assign(window, { TrackRow, TrackRowSkeleton, PlayingIndicator });
