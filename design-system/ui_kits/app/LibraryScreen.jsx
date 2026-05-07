// LibraryScreen.jsx — YourTube UI Kit

const SAMPLE_PLAYLISTS = [
  { id: 'pl1', name: 'Lofi Chill', trackCount: 24, colors: ['#3D1F7A','#1F3A7A','#1F6A4A','#6A3A1F'] },
  { id: 'pl2', name: 'Rock Classics', trackCount: 18, colors: ['#7A1F1F','#4A3A1F','#1F4A3A','#3A1F7A'] },
  { id: 'pl3', name: 'Focus Mode', trackCount: 12, colors: ['#1F4A6A','#3A4A1F','#1F1F7A','#6A4A1F'] },
  { id: 'pl4', name: 'Morning Energy', trackCount: 9, colors: ['#6A1F3A','#1F6A3A','#3A6A1F','#1F3A6A'] },
];

const RECENT_TRACKS = [
  { videoId: 'h1', title: 'Stairway to Heaven', channel: 'Led Zeppelin', durationSec: 482 },
  { videoId: 'h2', title: 'Hotel California (Live)', channel: 'Eagles', durationSec: 438 },
];

const PlaylistCover = ({ colors, size = 56 }) => (
  <div style={{ width: size, height: size, borderRadius: 8, overflow: 'hidden', flexShrink: 0, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2, background: '#2C2C2E' }}>
    {colors.map((c, i) => <div key={i} style={{ background: c }} />)}
  </div>
);

const LibraryScreen = ({ currentTrack, onPlayTrack, onNavigateHistory }) => {
  const [showNewPlaylist, setShowNewPlaylist] = React.useState(false);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', background: '#0F0F0F', overflowY: 'auto' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 16px 8px' }}>
        <div style={{ fontSize: 24, fontWeight: 600, color: '#FFF', fontFamily: 'var(--font-sans)', letterSpacing: '-0.02em' }}>Library</div>
        <button onClick={() => setShowNewPlaylist(true)} style={{ width: 36, height: 36, borderRadius: '50%', background: '#1C1C1E', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#8B5CF6', transition: 'background 150ms ease' }}
          onMouseEnter={e => e.currentTarget.style.background = '#2C2C2E'}
          onMouseLeave={e => e.currentTarget.style.background = '#1C1C1E'}
          aria-label="New playlist"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        </button>
      </div>

      {/* Recently Played entry */}
      <button onClick={onNavigateHistory} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 16px', background: 'transparent', border: 'none', cursor: 'pointer', width: '100%', textAlign: 'left', transition: 'background 150ms ease', borderBottom: '1px solid #3A3A3C' }}
        onMouseEnter={e => e.currentTarget.style.background = '#1C1C1E'}
        onMouseLeave={e => e.currentTarget.style.background = 'transparent'}
      >
        <div style={{ width: 56, height: 56, borderRadius: 8, background: 'rgba(139,92,246,0.15)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#8B5CF6" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 .49-4.95"/></svg>
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 15, fontWeight: 500, color: '#FFF', fontFamily: 'var(--font-sans)' }}>Recently Played</div>
          <div style={{ fontSize: 13, color: 'rgba(235,235,245,0.5)', fontFamily: 'var(--font-sans)', marginTop: 2 }}>{RECENT_TRACKS.length} tracks</div>
        </div>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="rgba(235,235,245,0.3)" strokeWidth="2.5" strokeLinecap="round"><polyline points="9,18 15,12 9,6"/></svg>
      </button>

      {/* Section header */}
      <div style={{ padding: '16px 16px 8px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ fontSize: 13, fontWeight: 600, color: 'rgba(235,235,245,0.4)', letterSpacing: '0.06em', textTransform: 'uppercase' }}>Playlists</div>
        <div style={{ fontSize: 13, color: 'rgba(235,235,245,0.4)', fontFamily: 'var(--font-sans)' }}>{SAMPLE_PLAYLISTS.length}</div>
      </div>

      {/* Playlist list */}
      <div style={{ background: '#1C1C1E', borderRadius: 12, margin: '0 0 8px', overflow: 'hidden' }}>
        {SAMPLE_PLAYLISTS.map((pl, i) => (
          <div key={pl.id} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 16px', cursor: 'pointer', borderTop: i > 0 ? '1px solid #3A3A3C' : 'none', transition: 'background 150ms ease' }}
            onMouseEnter={e => e.currentTarget.style.background = '#2C2C2E'}
            onMouseLeave={e => e.currentTarget.style.background = 'transparent'}
          >
            <PlaylistCover colors={pl.colors} />
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 15, fontWeight: 500, color: '#FFF', fontFamily: 'var(--font-sans)' }}>{pl.name}</div>
              <div style={{ fontSize: 12, color: 'rgba(235,235,245,0.5)', fontFamily: 'var(--font-sans)', marginTop: 2 }}>{pl.trackCount} tracks</div>
            </div>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="rgba(235,235,245,0.3)" strokeWidth="2.5" strokeLinecap="round"><polyline points="9,18 15,12 9,6"/></svg>
          </div>
        ))}
      </div>

      {/* Import / Export */}
      <div style={{ display: 'flex', gap: 8, padding: '0 0 16px' }}>
        <button style={{ flex: 1, height: 44, background: '#1C1C1E', border: '1px solid #3A3A3C', borderRadius: 12, fontFamily: 'var(--font-sans)', fontSize: 14, fontWeight: 500, color: '#8B5CF6', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, transition: 'background 150ms ease' }}
          onMouseEnter={e => e.currentTarget.style.background = '#2C2C2E'}
          onMouseLeave={e => e.currentTarget.style.background = '#1C1C1E'}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17,8 12,3 7,8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
          Import
        </button>
        <button style={{ flex: 1, height: 44, background: '#1C1C1E', border: '1px solid #3A3A3C', borderRadius: 12, fontFamily: 'var(--font-sans)', fontSize: 14, fontWeight: 500, color: 'rgba(235,235,245,0.6)', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, transition: 'background 150ms ease' }}
          onMouseEnter={e => e.currentTarget.style.background = '#2C2C2E'}
          onMouseLeave={e => e.currentTarget.style.background = '#1C1C1E'}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7,10 12,15 17,10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          Export
        </button>
      </div>
    </div>
  );
};

Object.assign(window, { LibraryScreen, PlaylistCover });
