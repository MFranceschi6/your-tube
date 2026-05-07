// Wear OS, Android Auto, Android TV mockups for YourTube.
// Each form factor uses platform-idiomatic patterns; brand purple accent.

const WEAR_PALETTE = {
  bg: '#000000', surface: '#141414', surfaceVar: '#1F1F1F',
  accent: '#A78BFA', accentDim: '#8B5CF6',
  fg: '#FFFFFF', fgSec: 'rgba(235,235,245,0.65)', fgTer: 'rgba(235,235,245,0.40)',
  border: '#2A2A2A',
};

// Wear OS round watch face — Now Playing tile.
// Compose for Wear pattern: large central artwork + bottom transport.
const WearNowPlaying = () => {
  const p = WEAR_PALETTE;
  return (
    <div style={{
      width: 240, height: 240, borderRadius: '50%',
      background: 'radial-gradient(circle at 30% 30%, #2A1F4A 0%, #050507 80%)',
      overflow: 'hidden',
      position: 'relative',
      fontFamily: 'Roboto, sans-serif', color: p.fg,
      boxShadow: '0 0 0 8px #1A1A1A, 0 0 0 12px #0A0A0A, 0 20px 60px rgba(0,0,0,0.6)',
    }}>
      {/* curved arc progress */}
      <svg width="240" height="240" viewBox="0 0 240 240" style={{ position: 'absolute', inset: 0 }}>
        <circle cx="120" cy="120" r="112" stroke={p.surfaceVar} strokeWidth="3" fill="none" />
        <circle cx="120" cy="120" r="112" stroke={p.accent} strokeWidth="3" fill="none"
          strokeDasharray={2 * Math.PI * 112}
          strokeDashoffset={2 * Math.PI * 112 * (1 - 0.42)}
          transform="rotate(-90 120 120)" strokeLinecap="round" />
      </svg>

      {/* Top channel label */}
      <div style={{ position: 'absolute', top: 32, left: 0, right: 0, textAlign: 'center', fontSize: 10, color: p.fgSec, letterSpacing: '0.06em', textTransform: 'uppercase' }}>Queen Official</div>

      {/* Center artwork mini */}
      <div style={{
        position: 'absolute', top: 56, left: '50%', transform: 'translateX(-50%)',
        width: 56, height: 56, borderRadius: 14,
        background: `linear-gradient(135deg, ${p.accent}, ${p.accentDim})`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <div style={{ width: 18, height: 18, borderRadius: '50%', background: 'rgba(255,255,255,0.25)' }} />
      </div>

      {/* Track title */}
      <div style={{ position: 'absolute', top: 122, left: 28, right: 28, textAlign: 'center', fontSize: 13, fontWeight: 600, color: p.fg, lineHeight: 1.2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>Bohemian Rhapsody</div>

      {/* Transport row */}
      <div style={{ position: 'absolute', bottom: 32, left: 0, right: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10 }}>
        <div style={{ width: 36, height: 36, borderRadius: '50%', background: p.surfaceVar, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="skip_prev" size={16} color={p.fg} />
        </div>
        <div style={{ width: 48, height: 48, borderRadius: '50%', background: p.accent, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="pause" size={20} color="#1A1014" />
        </div>
        <div style={{ width: 36, height: 36, borderRadius: '50%', background: p.surfaceVar, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="skip_next" size={16} color={p.fg} />
        </div>
      </div>
    </div>
  );
};

// Wear OS Library tile — list with concentric pill buttons (Compose Wear pattern)
const WearLibrary = () => {
  const p = WEAR_PALETTE;
  return (
    <div style={{
      width: 240, height: 240, borderRadius: '50%',
      background: '#000',
      overflow: 'hidden',
      position: 'relative',
      fontFamily: 'Roboto, sans-serif', color: p.fg,
      boxShadow: '0 0 0 8px #1A1A1A, 0 0 0 12px #0A0A0A, 0 20px 60px rgba(0,0,0,0.6)',
    }}>
      <div style={{ position: 'absolute', top: 22, left: 0, right: 0, textAlign: 'center', fontSize: 11, color: p.fgSec, letterSpacing: '0.06em', textTransform: 'uppercase' }}>Library</div>
      <div style={{ position: 'absolute', top: 50, left: 30, right: 30, display: 'flex', flexDirection: 'column', gap: 6 }}>
        {SAMPLE_PLAYLISTS.slice(0, 3).map((pl, i) => (
          <div key={pl.id} style={{
            display: 'flex', alignItems: 'center', gap: 8,
            padding: '6px 12px',
            background: i === 0 ? `linear-gradient(90deg, ${p.accentDim}, ${p.accent})` : p.surfaceVar,
            borderRadius: 999, height: 38,
          }}>
            <PlaylistCover colors={pl.colors} size={26} radius={6} />
            <div style={{ fontSize: 11, fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{pl.name}</div>
          </div>
        ))}
      </div>
      <div style={{ position: 'absolute', bottom: 28, left: '50%', transform: 'translateX(-50%)', fontSize: 10, color: p.fgTer }}>· · ·</div>
    </div>
  );
};

// ─────────────────────────── Android Auto ───────────────────────────
// Android Auto's UI is system-rendered from MediaSession metadata + browse tree.
// You don't draw the chrome — you provide content + a color scheme. Below is
// an honest representation of how YourTube's data appears in Auto's frame.

const AUTO_PALETTE = {
  bg: '#1B1A1F', surface: '#26242C', surfaceVar: '#322F38',
  accent: '#C4B0FF', // Auto enforces high-contrast accents
  fg: '#FFFFFF', fgSec: 'rgba(255,255,255,0.7)',
};

const AndroidAutoNowPlaying = () => {
  const p = AUTO_PALETTE;
  return (
    <div style={{
      width: 1280, height: 600,
      background: p.bg,
      display: 'grid', gridTemplateColumns: '96px 1fr',
      fontFamily: 'Roboto, sans-serif', color: p.fg,
      borderRadius: 12, overflow: 'hidden',
      boxShadow: '0 20px 60px rgba(0,0,0,0.5)',
    }}>
      {/* Left rail (system) */}
      <div style={{ background: '#0F0E12', display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '20px 0', gap: 28 }}>
        {[
          { name: 'home', sel: false },
          { name: 'play_circle', sel: true },
          { name: 'library', sel: false },
          { name: 'search', sel: false },
        ].map((t, i) => (
          <div key={i} style={{
            width: 56, height: 56, borderRadius: '50%',
            background: t.sel ? 'rgba(196,176,255,0.18)' : 'transparent',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <Icon name={t.name} size={28} color={t.sel ? p.accent : p.fgSec} strokeWidth={2} />
          </div>
        ))}
      </div>

      {/* Now Playing pane */}
      <div style={{ padding: '28px 40px', display: 'grid', gridTemplateColumns: '320px 1fr', gap: 40 }}>
        {/* Artwork */}
        <div style={{
          width: 320, height: 320, borderRadius: 16,
          background: `linear-gradient(135deg, ${p.accent}, #6D28D9)`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: '0 16px 48px rgba(0,0,0,0.5)',
        }}>
          <div style={{ width: 110, height: 110, borderRadius: '50%', border: '4px solid rgba(255,255,255,0.18)' }} />
        </div>

        {/* Meta + transport */}
        <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'space-between', paddingTop: 24 }}>
          <div>
            <div style={{ fontSize: 14, color: p.fgSec, marginBottom: 8, letterSpacing: '0.04em', textTransform: 'uppercase' }}>Playing from playlist · Rock Classics</div>
            <div style={{ fontSize: 44, fontWeight: 500, letterSpacing: '-0.02em', lineHeight: 1.1, marginBottom: 8 }}>Bohemian Rhapsody</div>
            <div style={{ fontSize: 22, color: p.fgSec }}>Queen Official</div>
          </div>

          {/* Scrubber */}
          <div style={{ marginTop: 32 }}>
            <div style={{ height: 6, background: p.surfaceVar, borderRadius: 999, position: 'relative' }}>
              <div style={{ height: '100%', width: '42%', background: p.accent, borderRadius: 999 }} />
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8, fontSize: 16, color: p.fgSec, fontVariantNumeric: 'tabular-nums' }}>
              <span>2:28</span><span>5:54</span>
            </div>
          </div>

          {/* Big touch transport */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 32, marginTop: 28 }}>
            <div style={{ width: 72, height: 72, borderRadius: '50%', background: p.surfaceVar, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <Icon name="skip_prev" size={36} color={p.fg} />
            </div>
            <div style={{ width: 96, height: 96, borderRadius: '50%', background: p.accent, display: 'flex', alignItems: 'center', justifyContent: 'center', boxShadow: `0 8px 32px ${p.accent}55` }}>
              <Icon name="pause" size={42} color="#1A1014" />
            </div>
            <div style={{ width: 72, height: 72, borderRadius: '50%', background: p.surfaceVar, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <Icon name="skip_next" size={36} color={p.fg} />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

// Browse view — Auto's content browser screen
const AndroidAutoBrowse = () => {
  const p = AUTO_PALETTE;
  return (
    <div style={{
      width: 1280, height: 600,
      background: p.bg,
      display: 'grid', gridTemplateColumns: '96px 1fr',
      fontFamily: 'Roboto, sans-serif', color: p.fg,
      borderRadius: 12, overflow: 'hidden',
      boxShadow: '0 20px 60px rgba(0,0,0,0.5)',
    }}>
      <div style={{ background: '#0F0E12', display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '20px 0', gap: 28 }}>
        {[
          { name: 'home', sel: false },
          { name: 'play_circle', sel: false },
          { name: 'library', sel: true },
          { name: 'search', sel: false },
        ].map((t, i) => (
          <div key={i} style={{
            width: 56, height: 56, borderRadius: '50%',
            background: t.sel ? 'rgba(196,176,255,0.18)' : 'transparent',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <Icon name={t.name} size={28} color={t.sel ? p.accent : p.fgSec} strokeWidth={2} />
          </div>
        ))}
      </div>
      <div style={{ padding: '28px 40px' }}>
        <div style={{ fontSize: 32, fontWeight: 500, marginBottom: 24 }}>Library</div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 20 }}>
          {SAMPLE_PLAYLISTS.map((pl) => (
            <div key={pl.id} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <PlaylistCover colors={pl.colors} size={220} radius={12} />
              <div style={{ fontSize: 18, fontWeight: 500 }}>{pl.name}</div>
              <div style={{ fontSize: 14, color: p.fgSec }}>{pl.trackCount} tracks</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

// ─────────────────────────── Android TV ───────────────────────────
// 10-foot UI, focus-based navigation. Compose for TV.

const TV_PALETTE = {
  bg: '#0A0A0B', surface: 'rgba(28,28,30,0.85)', surfaceVar: 'rgba(60,60,67,0.5)',
  accent: '#A78BFA', accentDim: '#8B5CF6',
  fg: '#FFFFFF', fgSec: 'rgba(235,235,245,0.7)',
};

const AndroidTVHome = () => {
  const p = TV_PALETTE;
  return (
    <div style={{
      width: 1280, height: 720,
      background: `radial-gradient(ellipse at 75% 0%, rgba(139,92,246,0.25) 0%, ${p.bg} 60%)`,
      fontFamily: 'Roboto, sans-serif', color: p.fg,
      borderRadius: 8, overflow: 'hidden', position: 'relative',
      boxShadow: '0 20px 60px rgba(0,0,0,0.5)',
    }}>
      {/* Top nav */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '32px 64px 16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 36 }}>
          <div style={{ fontSize: 22, fontWeight: 600, color: p.accent, letterSpacing: '-0.02em' }}>YourTube</div>
          {['Home', 'Library', 'Search', 'Settings'].map((t, i) => (
            <div key={t} style={{
              fontSize: 16, fontWeight: i === 0 ? 600 : 400,
              color: i === 0 ? p.fg : p.fgSec,
              padding: '8px 4px',
              borderBottom: i === 0 ? `2px solid ${p.accent}` : '2px solid transparent',
            }}>{t}</div>
          ))}
        </div>
        <div style={{ fontSize: 14, color: p.fgSec }}>9:41 PM</div>
      </div>

      {/* Hero featured */}
      <div style={{ padding: '0 64px 24px' }}>
        <div style={{ display: 'flex', gap: 32, height: 240 }}>
          <div style={{
            width: 360, height: 240, borderRadius: 12,
            background: `linear-gradient(135deg, ${p.accent}, ${p.accentDim})`,
            border: `3px solid ${p.accent}`,
            boxShadow: `0 0 0 6px rgba(167,139,250,0.25), 0 16px 48px rgba(0,0,0,0.5)`,
          }} />
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
            <div style={{ fontSize: 12, color: p.accent, letterSpacing: '0.08em', textTransform: 'uppercase', marginBottom: 8 }}>Continue listening</div>
            <div style={{ fontSize: 44, fontWeight: 500, letterSpacing: '-0.02em', lineHeight: 1.1, marginBottom: 8 }}>Bohemian Rhapsody</div>
            <div style={{ fontSize: 18, color: p.fgSec, marginBottom: 24 }}>Queen Official · 5:54</div>
            <div style={{ display: 'flex', gap: 12 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '12px 24px', borderRadius: 999, background: p.accent, color: '#1A1014', fontSize: 16, fontWeight: 600 }}>
                <Icon name="play" size={18} color="#1A1014" /> Resume
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '12px 20px', borderRadius: 999, background: p.surfaceVar, color: p.fg, fontSize: 16, fontWeight: 500, border: `1px solid ${p.fgSec}33` }}>
                <Icon name="queue" size={18} color={p.fg} /> Queue
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Carousel */}
      <div style={{ padding: '8px 64px' }}>
        <div style={{ fontSize: 18, fontWeight: 500, marginBottom: 14, color: p.fg }}>Your playlists</div>
        <div style={{ display: 'flex', gap: 18 }}>
          {SAMPLE_PLAYLISTS.map((pl, i) => (
            <div key={pl.id} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <div style={{
                width: 200, height: 200,
                border: i === 0 ? `3px solid ${p.accent}` : '3px solid transparent',
                borderRadius: 12,
                boxShadow: i === 0 ? `0 0 0 4px rgba(167,139,250,0.2)` : 'none',
                padding: i === 0 ? 0 : 3,
              }}>
                <PlaylistCover colors={pl.colors} size={i === 0 ? 194 : 194} radius={9} />
              </div>
              <div style={{ fontSize: 15, fontWeight: i === 0 ? 600 : 400, color: i === 0 ? p.fg : p.fgSec }}>{pl.name}</div>
              <div style={{ fontSize: 12, color: p.fgSec, marginTop: -4 }}>{pl.trackCount} tracks</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

const AndroidTVNowPlaying = () => {
  const p = TV_PALETTE;
  return (
    <div style={{
      width: 1280, height: 720,
      background: `linear-gradient(160deg, #2C1B4E 0%, #14141C 50%, ${p.bg} 100%)`,
      fontFamily: 'Roboto, sans-serif', color: p.fg,
      borderRadius: 8, overflow: 'hidden', position: 'relative',
      boxShadow: '0 20px 60px rgba(0,0,0,0.5)',
      display: 'grid', gridTemplateColumns: '1fr 1fr',
    }}>
      {/* Left: artwork */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 80 }}>
        <div style={{
          width: 480, height: 480, borderRadius: 24,
          background: `linear-gradient(135deg, ${p.accent} 0%, ${p.accentDim} 100%)`,
          boxShadow: '0 24px 80px rgba(0,0,0,0.6)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <div style={{ width: 140, height: 140, borderRadius: '50%', border: '6px solid rgba(255,255,255,0.18)' }} />
        </div>
      </div>

      {/* Right: meta + queue */}
      <div style={{ padding: '80px 80px 80px 0', display: 'flex', flexDirection: 'column', gap: 20 }}>
        <div style={{ fontSize: 13, color: p.accent, letterSpacing: '0.08em', textTransform: 'uppercase' }}>Now Playing</div>
        <div style={{ fontSize: 52, fontWeight: 500, letterSpacing: '-0.02em', lineHeight: 1.05 }}>Bohemian Rhapsody</div>
        <div style={{ fontSize: 22, color: p.fgSec, marginBottom: 8 }}>Queen Official · Rock Classics</div>

        <div>
          <div style={{ height: 6, background: p.surfaceVar, borderRadius: 999 }}>
            <div style={{ height: '100%', width: '42%', background: p.accent, borderRadius: 999 }} />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8, fontSize: 16, color: p.fgSec, fontVariantNumeric: 'tabular-nums' }}>
            <span>2:28</span><span>5:54</span>
          </div>
        </div>

        {/* Transport with focus ring */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 20, marginTop: 8 }}>
          <div style={{ width: 64, height: 64, borderRadius: '50%', background: p.surfaceVar, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Icon name="fast_rewind" size={28} color={p.fg} />
          </div>
          <div style={{ width: 64, height: 64, borderRadius: '50%', background: p.surfaceVar, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Icon name="skip_prev" size={28} color={p.fg} />
          </div>
          <div style={{
            width: 88, height: 88, borderRadius: '50%', background: p.accent,
            border: `4px solid ${p.fg}`,
            boxShadow: `0 0 0 6px rgba(167,139,250,0.25), 0 12px 32px ${p.accent}55`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <Icon name="pause" size={36} color="#1A1014" />
          </div>
          <div style={{ width: 64, height: 64, borderRadius: '50%', background: p.surfaceVar, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Icon name="skip_next" size={28} color={p.fg} />
          </div>
          <div style={{ width: 64, height: 64, borderRadius: '50%', background: p.surfaceVar, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Icon name="fast_forward" size={28} color={p.fg} />
          </div>
        </div>

        <div style={{ marginTop: 28 }}>
          <div style={{ fontSize: 13, color: p.fgSec, letterSpacing: '0.06em', textTransform: 'uppercase', marginBottom: 12 }}>Up next</div>
          {SAMPLE_QUEUE.slice(0, 3).map((t, i) => (
            <div key={t.videoId} style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '8px 0', borderTop: i === 0 ? 'none' : `1px solid ${p.surfaceVar}` }}>
              <div style={{ width: 40, height: 40, borderRadius: 6, background: p.surfaceVar, flexShrink: 0 }} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 15, fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{t.title}</div>
                <div style={{ fontSize: 13, color: p.fgSec }}>{t.channel}</div>
              </div>
              <div style={{ fontSize: 13, color: p.fgSec, fontVariantNumeric: 'tabular-nums' }}>{fmtTime(t.durationSec)}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

Object.assign(window, {
  WearNowPlaying, WearLibrary,
  AndroidAutoNowPlaying, AndroidAutoBrowse,
  AndroidTVHome, AndroidTVNowPlaying,
});
