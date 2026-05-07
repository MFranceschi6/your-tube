// Shared atoms and sample data for Android version mockups.
// Loaded as <script type="text/babel"> — exports to window.

const SAMPLE_TRACK = {
  videoId: 'cur',
  title: 'Bohemian Rhapsody',
  channel: 'Queen Official',
  durationSec: 354,
};

const SAMPLE_QUEUE = [
  { videoId: 'q1', title: "Don't Stop Me Now", channel: 'Queen Official', durationSec: 209 },
  { videoId: 'q2', title: 'Somebody to Love', channel: 'Queen Official', durationSec: 296 },
  { videoId: 'q3', title: 'Under Pressure', channel: 'Queen & Bowie', durationSec: 248 },
  { videoId: 'q4', title: 'Radio Ga Ga', channel: 'Queen Official', durationSec: 343 },
];

const SAMPLE_PLAYLISTS = [
  { id: 'pl1', name: 'Lofi Chill', trackCount: 24, colors: ['#3D1F7A', '#1F3A7A', '#1F6A4A', '#6A3A1F'] },
  { id: 'pl2', name: 'Rock Classics', trackCount: 18, colors: ['#7A1F1F', '#4A3A1F', '#1F4A3A', '#3A1F7A'] },
  { id: 'pl3', name: 'Focus Mode', trackCount: 12, colors: ['#1F4A6A', '#3A4A1F', '#1F1F7A', '#6A4A1F'] },
  { id: 'pl4', name: 'Morning Energy', trackCount: 9, colors: ['#6A1F3A', '#1F6A3A', '#3A6A1F', '#1F3A6A'] },
];

const fmtTime = (sec) => {
  if (!sec) return '0:00';
  const m = Math.floor(sec / 60);
  const s = String(sec % 60).padStart(2, '0');
  return `${m}:${s}`;
};

// ───────── Material Symbols Rounded glyphs (paths from Lucide as proxy) ─────────
const Icon = ({ name, size = 24, color = 'currentColor', strokeWidth = 2 }) => {
  const common = { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: color, strokeWidth, strokeLinecap: 'round', strokeLinejoin: 'round' };
  switch (name) {
    case 'search': return <svg {...common}><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>;
    case 'library': return <svg {...common}><path d="M21 15V6"/><path d="M18.5 18a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z"/><path d="M12 12H3"/><path d="M16 6H3"/><path d="M12 18H3"/></svg>;
    case 'history': return <svg {...common}><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/></svg>;
    case 'play_circle': return <svg {...common}><circle cx="12" cy="12" r="10"/><polygon points="10,8 16,12 10,16" fill={color} stroke="none"/></svg>;
    case 'settings': return <svg {...common}><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z"/></svg>;
    case 'play': return <svg {...common} fill={color}><polygon points="5,3 19,12 5,21"/></svg>;
    case 'pause': return <svg {...common} fill={color}><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg>;
    case 'skip_next': return <svg {...common}><polygon points="5,4 15,12 5,20" fill={color}/><line x1="19" y1="5" x2="19" y2="19"/></svg>;
    case 'skip_prev': return <svg {...common}><polygon points="19,20 9,12 19,4" fill={color}/><line x1="5" y1="19" x2="5" y2="5"/></svg>;
    case 'queue': return <svg {...common}><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>;
    case 'more_vert': return <svg {...common}><circle cx="12" cy="5" r="1" fill={color}/><circle cx="12" cy="12" r="1" fill={color}/><circle cx="12" cy="19" r="1" fill={color}/></svg>;
    case 'shuffle': return <svg {...common}><polyline points="16,3 21,3 21,8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21,16 21,21 16,21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>;
    case 'repeat': return <svg {...common}><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></svg>;
    case 'favorite': return <svg {...common}><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>;
    case 'add': return <svg {...common}><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>;
    case 'chevron_down': return <svg {...common}><polyline points="6,9 12,15 18,9"/></svg>;
    case 'chevron_right': return <svg {...common}><polyline points="9,18 15,12 9,6"/></svg>;
    case 'mic': return <svg {...common}><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>;
    case 'cast': return <svg {...common}><path d="M2 16.1A5 5 0 0 1 5.9 20M2 12.05A9 9 0 0 1 9.95 20M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6"/><line x1="2" y1="20" x2="2.01" y2="20"/></svg>;
    case 'volume': return <svg {...common}><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" fill={color}/><path d="M19.07 4.93a10 10 0 0 1 0 14.14M15.54 8.46a5 5 0 0 1 0 7.07"/></svg>;
    case 'menu': return <svg {...common}><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>;
    case 'home': return <svg {...common}><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>;
    case 'arrow_back': return <svg {...common}><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>;
    case 'fast_forward': return <svg {...common}><polygon points="13 19 22 12 13 5 13 19" fill={color}/><polygon points="2 19 11 12 2 5 2 19" fill={color}/></svg>;
    case 'fast_rewind': return <svg {...common}><polygon points="11 19 2 12 11 5 11 19" fill={color}/><polygon points="22 19 13 12 22 5 22 19" fill={color}/></svg>;
    default: return null;
  }
};

// 4-up playlist cover — used in Library across all variants
const PlaylistCover = ({ colors, size = 56, radius = 8 }) => (
  <div style={{ width: size, height: size, borderRadius: radius, overflow: 'hidden', flexShrink: 0, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.5, background: '#2C2C2E' }}>
    {colors.map((c, i) => <div key={i} style={{ background: c }} />)}
  </div>
);

// Status bar component, parameterized by API generation.
// generation: 'baseline' (24-30) | 'you' (31-33) | 'modern' (34+)
const StatusBar = ({ tone = 'dark', time = '9:41', api = 'baseline' }) => {
  const fg = tone === 'dark' ? '#FFFFFF' : '#000000';
  const bg = tone === 'dark' ? '#0F0F0F' : '#FAFAFA';
  return (
    <div style={{
      height: 28, padding: '0 18px',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      background: api === 'modern' ? 'transparent' : bg,
      color: fg,
      fontSize: 13, fontWeight: 600, fontFamily: 'Roboto, system-ui, sans-serif',
      position: 'relative', zIndex: 5,
    }}>
      <span style={{ letterSpacing: '0.01em' }}>{time}</span>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        {/* signal */}
        <svg width="14" height="10" viewBox="0 0 14 10" fill={fg}><rect x="0" y="6" width="2" height="4" rx="0.5"/><rect x="3.5" y="4" width="2" height="6" rx="0.5"/><rect x="7" y="2" width="2" height="8" rx="0.5"/><rect x="10.5" y="0" width="2" height="10" rx="0.5"/></svg>
        {/* wifi */}
        <svg width="14" height="10" viewBox="0 0 14 10" fill={fg}><path d="M7 9.5a1 1 0 100-2 1 1 0 000 2zM3.2 6.5a5.5 5.5 0 017.6 0l1-1a7 7 0 00-9.6 0l1 1zm-3-3a9.5 9.5 0 0113.6 0l1-1a11 11 0 00-15.6 0l1 1z"/></svg>
        {/* battery */}
        <div style={{ width: 24, height: 11, border: `1.2px solid ${fg}`, borderRadius: 3, position: 'relative', padding: 1.2 }}>
          <div style={{ width: '85%', height: '100%', background: fg, borderRadius: 1 }} />
          <div style={{ position: 'absolute', right: -2.5, top: 3, width: 1.5, height: 4, background: fg, borderRadius: 1 }} />
        </div>
      </div>
    </div>
  );
};

// Generic Android nav bar (3-button) for legacy + gesture pill for modern.
const NavBar = ({ generation = 'baseline', tone = 'dark' }) => {
  const fg = tone === 'dark' ? 'rgba(255,255,255,0.85)' : 'rgba(0,0,0,0.85)';
  if (generation === 'modern') {
    // gesture nav pill, edge-to-edge
    return (
      <div style={{ height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'transparent' }}>
        <div style={{ width: 130, height: 4, borderRadius: 999, background: fg }} />
      </div>
    );
  }
  return (
    <div style={{ height: 38, background: tone === 'dark' ? '#0F0F0F' : '#FAFAFA', display: 'flex', alignItems: 'center', justifyContent: 'space-around', borderTop: `1px solid ${tone === 'dark' ? '#1C1C1E' : '#E5E5EA'}` }}>
      <svg width="14" height="14" viewBox="0 0 24 24" fill={fg}><polygon points="20 5 9 16 5 12 3 14 9 22 22 7"/></svg>
      <svg width="14" height="14" viewBox="0 0 24 24" fill={fg}><circle cx="12" cy="12" r="9"/></svg>
      <svg width="14" height="14" viewBox="0 0 24 24" fill={fg}><rect x="4" y="4" width="16" height="16" rx="2"/></svg>
    </div>
  );
};

Object.assign(window, {
  SAMPLE_TRACK, SAMPLE_QUEUE, SAMPLE_PLAYLISTS, fmtTime,
  Icon, PlaylistCover, StatusBar, NavBar,
});
