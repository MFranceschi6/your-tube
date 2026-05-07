// HistoryScreen.jsx + SettingsScreen.jsx — YourTube UI Kit

const HISTORY_TRACKS = [
  { videoId: 'h1', title: 'Stairway to Heaven', channel: 'Led Zeppelin', durationSec: 482 },
  { videoId: 'h2', title: 'Hotel California (Live)', channel: 'Eagles', durationSec: 438 },
  { videoId: 'h3', title: 'Bohemian Rhapsody', channel: 'Queen', durationSec: 355 },
  { videoId: 'h4', title: 'Comfortably Numb', channel: 'Pink Floyd', durationSec: 382 },
  { videoId: 'h5', title: 'Sweet Child O\' Mine', channel: 'Guns N\' Roses', durationSec: 356 },
  { videoId: 'h6', title: 'November Rain', channel: 'Guns N\' Roses', durationSec: 537 },
];

const HistoryScreen = ({ currentTrack, onPlayTrack, onBack }) => (
  <div style={{ display: 'flex', flexDirection: 'column', height: '100%', background: '#0F0F0F' }}>
    {/* Header */}
    <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '12px 16px 8px', borderBottom: '1px solid #3A3A3C' }}>
      <button onClick={onBack} style={{ width: 36, height: 36, borderRadius: '50%', background: 'transparent', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#8B5CF6', transition: 'background 150ms ease' }}
        onMouseEnter={e => e.currentTarget.style.background = '#1C1C1E'}
        onMouseLeave={e => e.currentTarget.style.background = 'transparent'}
        aria-label="Back"
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><polyline points="15,18 9,12 15,6"/></svg>
      </button>
      <div style={{ fontSize: 20, fontWeight: 600, color: '#FFF', fontFamily: 'var(--font-sans)', letterSpacing: '-0.02em' }}>Recently Played</div>
    </div>

    {/* List */}
    <div style={{ flex: 1, overflowY: 'auto' }}>
      {HISTORY_TRACKS.length === 0 ? (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, padding: '48px 32px', textAlign: 'center' }}>
          <div style={{ width: 52, height: 52, background: '#1C1C1E', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="rgba(235,235,245,0.3)" strokeWidth="1.5" strokeLinecap="round"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 .49-4.95"/></svg>
          </div>
          <div style={{ fontSize: 16, fontWeight: 600, color: '#FFF', fontFamily: 'var(--font-sans)' }}>Nothing played yet</div>
          <div style={{ fontSize: 14, color: 'rgba(235,235,245,0.5)', fontFamily: 'var(--font-sans)' }}>Search for something to start listening</div>
        </div>
      ) : (
        HISTORY_TRACKS.map(t => (
          <TrackRow key={t.videoId} track={t} isPlaying={currentTrack?.videoId === t.videoId} onPlay={() => onPlayTrack(t)} />
        ))
      )}
    </div>
  </div>
);

// ─── Settings ───────────────────────────────────────────────────────────────

const SettingsScreen = ({ darkMode, onToggleDark }) => {
  const [streamQuality, setStreamQuality] = React.useState('high');
  const [notifications, setNotifications] = React.useState(true);
  const [cacheEnabled, setCacheEnabled] = React.useState(true);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', background: '#0F0F0F', overflowY: 'auto' }}>
      <div style={{ padding: '16px 16px 8px' }}>
        <div style={{ fontSize: 24, fontWeight: 600, color: '#FFF', fontFamily: 'var(--font-sans)', letterSpacing: '-0.02em' }}>Settings</div>
      </div>

      <SettingsSection title="Appearance">
        <SettingsToggle label="Dark mode" value={darkMode} onChange={onToggleDark} />
      </SettingsSection>

      <SettingsSection title="Playback">
        <SettingsSelect label="Stream quality" value={streamQuality} options={[{v:'low',l:'Low (64 kbps)'},{v:'medium',l:'Medium (128 kbps)'},{v:'high',l:'High (256 kbps)'}]} onChange={setStreamQuality} />
        <SettingsToggle label="Cache streams" value={cacheEnabled} onChange={setCacheEnabled} sublabel="Reduce repeated loading" />
      </SettingsSection>

      <SettingsSection title="Notifications">
        <SettingsToggle label="Download complete" value={notifications} onChange={setNotifications} />
      </SettingsSection>

      <SettingsSection title="Data">
        <SettingsItem label="Clear playback cache" destructive onTap={() => {}} />
        <SettingsItem label="Clear history" destructive onTap={() => {}} />
        <SettingsItem label="Export all playlists" onTap={() => {}} />
      </SettingsSection>

      <div style={{ padding: '24px 16px 32px', textAlign: 'center' }}>
        <div style={{ fontSize: 13, color: 'rgba(235,235,245,0.25)', fontFamily: 'var(--font-sans)' }}>YourTube v0.1.0 — MVP</div>
        <div style={{ fontSize: 12, color: 'rgba(235,235,245,0.15)', fontFamily: 'var(--font-sans)', marginTop: 4 }}>No backend · Stream URLs resolved locally</div>
      </div>
    </div>
  );
};

const SettingsSection = ({ title, children }) => (
  <div style={{ marginBottom: 24 }}>
    <div style={{ padding: '0 16px 8px', fontSize: 13, fontWeight: 600, color: 'rgba(235,235,245,0.4)', letterSpacing: '0.06em', textTransform: 'uppercase' }}>{title}</div>
    <div style={{ background: '#1C1C1E', overflow: 'hidden' }}>
      {React.Children.map(children, (child, i) =>
        React.cloneElement(child, { isFirst: i === 0 })
      )}
    </div>
  </div>
);

const SettingsToggle = ({ label, value, onChange, sublabel, isFirst }) => (
  <div style={{ display: 'flex', alignItems: 'center', padding: '14px 16px', borderTop: isFirst ? 'none' : '1px solid #3A3A3C', gap: 12 }}>
    <div style={{ flex: 1 }}>
      <div style={{ fontSize: 15, color: '#FFF', fontFamily: 'var(--font-sans)' }}>{label}</div>
      {sublabel && <div style={{ fontSize: 12, color: 'rgba(235,235,245,0.4)', fontFamily: 'var(--font-sans)', marginTop: 2 }}>{sublabel}</div>}
    </div>
    <div onClick={() => onChange(!value)} style={{ width: 50, height: 30, borderRadius: 15, background: value ? '#8B5CF6' : '#3A3A3C', position: 'relative', cursor: 'pointer', transition: 'background 200ms ease', flexShrink: 0 }}>
      <div style={{ position: 'absolute', top: 3, left: value ? 23 : 3, width: 24, height: 24, background: 'white', borderRadius: '50%', transition: 'left 200ms ease', boxShadow: '0 2px 4px rgba(0,0,0,0.3)' }} />
    </div>
  </div>
);

const SettingsSelect = ({ label, value, options, onChange, isFirst }) => (
  <div style={{ display: 'flex', alignItems: 'center', padding: '14px 16px', borderTop: isFirst ? 'none' : '1px solid #3A3A3C', gap: 12 }}>
    <div style={{ flex: 1, fontSize: 15, color: '#FFF', fontFamily: 'var(--font-sans)' }}>{label}</div>
    <select value={value} onChange={e => onChange(e.target.value)} style={{ background: '#2C2C2E', border: '1px solid #3A3A3C', borderRadius: 8, color: '#8B5CF6', fontFamily: 'var(--font-sans)', fontSize: 14, padding: '4px 8px', cursor: 'pointer' }}>
      {options.map(o => <option key={o.v} value={o.v}>{o.l}</option>)}
    </select>
  </div>
);

const SettingsItem = ({ label, destructive, onTap, isFirst }) => (
  <button onClick={onTap} style={{ display: 'flex', alignItems: 'center', padding: '14px 16px', borderTop: isFirst ? 'none' : '1px solid #3A3A3C', width: '100%', background: 'transparent', border: 'none', cursor: 'pointer', textAlign: 'left', transition: 'background 150ms ease' }}
    onMouseEnter={e => e.currentTarget.style.background = '#2C2C2E'}
    onMouseLeave={e => e.currentTarget.style.background = 'transparent'}
  >
    <span style={{ flex: 1, fontSize: 15, color: destructive ? '#FF453A' : '#FFF', fontFamily: 'var(--font-sans)' }}>{label}</span>
    {!destructive && <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="rgba(235,235,245,0.3)" strokeWidth="2.5" strokeLinecap="round"><polyline points="9,18 15,12 9,6"/></svg>}
  </button>
);

Object.assign(window, { HistoryScreen, SettingsScreen });
