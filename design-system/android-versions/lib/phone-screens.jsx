// Phone Home / Library / Now-Playing screens, parameterized by API generation.
// generation: 'baseline' (24-30) | 'you' (31-33) | 'modern' (34+)
//
// Visual diffs encoded:
//   baseline → flat M3 with brand purple, no edge-to-edge, opaque status bar,
//              tab bar with bottom nav (M3 NavigationBar), corner radius 12.
//   you      → dynamic color baked from wallpaper (sample: warm coral); larger
//              tonal surfaces, themed card containers, animated FAB; pill nav.
//   modern   → edge-to-edge with translucent surfaces, shape morphing buttons,
//              gesture pill, expressive motion bar at top, larger radii.

const PALETTES = {
  // brand purple fallback (24-30)
  baseline: {
    bg: '#0F0F0F', surface: '#1C1C1E', surfaceVar: '#2C2C2E',
    accent: '#8B5CF6', accentDim: '#7C3AED', accentSurface: 'rgba(139,92,246,0.16)',
    fg: '#FFFFFF', fgSec: 'rgba(235,235,245,0.65)', fgTer: 'rgba(235,235,245,0.40)',
    border: '#3A3A3C',
  },
  // Material You — system-derived warm palette (sample wallpaper: dusk coral)
  you: {
    bg: '#181210', surface: '#2A201D', surfaceVar: '#3B2D27',
    accent: '#FFB59C', accentDim: '#E89980', accentSurface: 'rgba(255,181,156,0.18)',
    fg: '#F8E8E1', fgSec: 'rgba(248,232,225,0.70)', fgTer: 'rgba(248,232,225,0.45)',
    border: '#4D3B33',
  },
  // Modern (Android 14+) — same brand but edge-to-edge w/ translucent layers
  modern: {
    bg: '#0A0A0B', surface: 'rgba(28,28,30,0.85)', surfaceVar: 'rgba(60,60,67,0.4)',
    accent: '#A78BFA', accentDim: '#8B5CF6', accentSurface: 'rgba(167,139,250,0.18)',
    fg: '#FFFFFF', fgSec: 'rgba(235,235,245,0.70)', fgTer: 'rgba(235,235,245,0.40)',
    border: 'rgba(255,255,255,0.08)',
  },
};

const M3TopAppBar = ({ title, generation, palette, trailing }) => {
  const isModern = generation === 'modern';
  return (
    <div style={{
      padding: isModern ? '8px 4px 4px' : '4px 4px',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      background: isModern ? 'transparent' : palette.bg,
    }}>
      <div style={{
        fontSize: generation === 'baseline' ? 22 : 28,
        fontWeight: generation === 'modern' ? 500 : 600,
        color: palette.fg,
        letterSpacing: '-0.02em',
        padding: '8px 16px',
        fontFamily: generation === 'you' ? '"Roboto Flex", Roboto, sans-serif' : 'Roboto, sans-serif',
      }}>{title}</div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, paddingRight: 8 }}>
        {trailing}
      </div>
    </div>
  );
};

const NavTabM3 = ({ generation, palette, active = 'home' }) => {
  const tabs = [
    { id: 'home', label: 'Home', icon: 'home' },
    { id: 'search', label: 'Search', icon: 'search' },
    { id: 'library', label: 'Library', icon: 'library' },
    { id: 'settings', label: 'Settings', icon: 'settings' },
  ];
  const isModern = generation === 'modern';
  const isYou = generation === 'you';
  return (
    <div style={{
      display: 'flex',
      background: isModern ? 'rgba(15,15,17,0.75)' : palette.bg,
      backdropFilter: isModern ? 'blur(20px) saturate(180%)' : undefined,
      borderTop: `1px solid ${palette.border}`,
      padding: '6px 4px',
      gap: 2,
    }}>
      {tabs.map(t => {
        const sel = t.id === active;
        return (
          <div key={t.id} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4, padding: '6px 0' }}>
            <div style={{
              width: 56, height: 28, borderRadius: 999,
              background: sel ? palette.accentSurface : 'transparent',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              transition: 'all 200ms ease',
            }}>
              <Icon name={t.icon} size={isYou ? 22 : 20} color={sel ? palette.accent : palette.fgSec} strokeWidth={sel ? 2.2 : 1.8} />
            </div>
            <div style={{
              fontSize: 11, fontWeight: sel ? 600 : 500,
              color: sel ? palette.fg : palette.fgSec,
              fontFamily: 'Roboto, sans-serif',
            }}>{t.label}</div>
          </div>
        );
      })}
    </div>
  );
};

// MiniPlayer matched to platform feel. Inset above nav.
const PhoneMiniPlayer = ({ generation, palette, isPlaying = true }) => {
  const isModern = generation === 'modern';
  return (
    <div style={{ padding: isModern ? '0 8px 6px' : '0 8px 6px' }}>
      <div style={{
        height: 60, padding: '6px 10px',
        background: isModern ? 'rgba(40,32,55,0.65)' : palette.surface,
        backdropFilter: isModern ? 'blur(20px)' : undefined,
        borderRadius: generation === 'you' ? 22 : 14,
        display: 'flex', alignItems: 'center', gap: 12,
        border: isModern ? `1px solid ${palette.border}` : 'none',
        boxShadow: '0 4px 16px rgba(0,0,0,0.4)',
        position: 'relative', overflow: 'hidden',
      }}>
        <div style={{
          width: 44, height: 44, borderRadius: generation === 'you' ? 12 : 8,
          background: `linear-gradient(135deg, ${palette.accentDim}, ${palette.accent})`,
          flexShrink: 0,
        }} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 13, fontWeight: 500, color: palette.fg, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', fontFamily: 'Roboto, sans-serif' }}>Bohemian Rhapsody</div>
          <div style={{ fontSize: 11, color: palette.fgSec, fontFamily: 'Roboto, sans-serif' }}>Queen Official</div>
        </div>
        <div style={{ width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name={isPlaying ? 'pause' : 'play'} size={20} color={palette.accent} />
        </div>
        <div style={{ width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="skip_next" size={20} color={palette.fg} />
        </div>
        <div style={{ position: 'absolute', left: 0, bottom: 0, height: 2, width: '38%', background: palette.accent }} />
      </div>
    </div>
  );
};

// HOME / FEED screen — vertical scroll of recommended cards & quick playlists
const PhoneHome = ({ generation }) => {
  const palette = PALETTES[generation];
  const isYou = generation === 'you';
  const isModern = generation === 'modern';
  return (
    <div style={{
      width: 360, height: 720,
      background: palette.bg,
      borderRadius: isModern ? 28 : 24,
      overflow: 'hidden',
      display: 'flex', flexDirection: 'column',
      fontFamily: 'Roboto, sans-serif', color: palette.fg,
      boxShadow: '0 20px 60px rgba(0,0,0,0.4)',
      position: 'relative',
    }}>
      <StatusBar api={generation === 'modern' ? 'modern' : 'baseline'} />
      {/* In modern, an artwork-tinted gradient bleeds behind status bar */}
      {isModern && (
        <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 220, background: 'linear-gradient(180deg, rgba(139,92,246,0.35) 0%, rgba(15,15,17,0) 100%)', pointerEvents: 'none' }} />
      )}
      <div style={{ flex: 1, overflow: 'hidden', position: 'relative' }}>
        <M3TopAppBar
          title="Home"
          generation={generation}
          palette={palette}
          trailing={<>
            <div style={{ width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Icon name="cast" size={20} color={palette.fgSec} strokeWidth={1.8} /></div>
            <div style={{ width: 28, height: 28, borderRadius: 999, background: `linear-gradient(135deg, ${palette.accent}, ${palette.accentDim})` }} />
          </>}
        />

        {/* Quick chips */}
        <div style={{ display: 'flex', gap: 8, padding: '4px 16px 12px', flexWrap: 'wrap' }}>
          {['All', 'Music', 'Podcasts', 'Live'].map((c, i) => (
            <div key={c} style={{
              padding: '6px 14px',
              background: i === 0 ? palette.accentSurface : palette.surfaceVar,
              border: `1px solid ${i === 0 ? palette.accent : 'transparent'}`,
              color: i === 0 ? palette.accent : palette.fg,
              borderRadius: 999, fontSize: 13, fontWeight: 500,
            }}>{c}</div>
          ))}
        </div>

        {/* Recently played grid (4-up M3 quick picks) */}
        <div style={{ padding: '0 16px', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginBottom: 16 }}>
          {SAMPLE_PLAYLISTS.slice(0, 4).map((pl, i) => (
            <div key={pl.id} style={{
              display: 'flex', alignItems: 'center', gap: 10,
              background: palette.surfaceVar,
              borderRadius: isYou ? 14 : 10,
              padding: 6, paddingRight: 10, height: 56,
            }}>
              <PlaylistCover colors={pl.colors} size={44} radius={isYou ? 10 : 6} />
              <div style={{ fontSize: 12, fontWeight: 500, color: palette.fg, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{pl.name}</div>
            </div>
          ))}
        </div>

        {/* Section title */}
        <div style={{ padding: '0 16px 8px', fontSize: 18, fontWeight: 600, letterSpacing: '-0.01em' }}>For you</div>

        {/* Horizontal-style cards row (rendered as a stack here for clarity) */}
        <div style={{ display: 'flex', gap: 12, padding: '0 16px', overflow: 'hidden' }}>
          {[0, 1].map(i => (
            <div key={i} style={{
              flex: '0 0 140px',
              borderRadius: isYou ? 18 : 12,
              overflow: 'hidden',
              background: palette.surface,
            }}>
              <div style={{
                width: '100%', height: 140,
                background: i === 0
                  ? `linear-gradient(135deg, ${palette.accent}, ${palette.accentDim})`
                  : 'linear-gradient(135deg, #1F3A7A, #1F6A4A)',
              }} />
              <div style={{ padding: '8px 10px' }}>
                <div style={{ fontSize: 13, fontWeight: 500, color: palette.fg, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{i === 0 ? 'Daily Mix 1' : 'Focus Flow'}</div>
                <div style={{ fontSize: 11, color: palette.fgSec, marginTop: 2 }}>Made for you</div>
              </div>
            </div>
          ))}
        </div>
      </div>

      <PhoneMiniPlayer generation={generation} palette={palette} />
      <NavTabM3 generation={generation} palette={palette} active="home" />
      <NavBar generation={generation === 'modern' ? 'modern' : 'baseline'} tone="dark" />
    </div>
  );
};

// NOW PLAYING screen
const PhoneNowPlaying = ({ generation }) => {
  const palette = PALETTES[generation];
  const isYou = generation === 'you';
  const isModern = generation === 'modern';
  const accentBg = isYou
    ? `linear-gradient(160deg, #4A2A1F 0%, #1F1614 60%, ${palette.bg} 100%)`
    : isModern
      ? `linear-gradient(160deg, #2A1F4A 0%, #14141C 55%, ${palette.bg} 100%)`
      : `linear-gradient(160deg, #2C1B4E 0%, #141414 55%, ${palette.bg} 100%)`;

  return (
    <div style={{
      width: 360, height: 720,
      background: accentBg,
      borderRadius: isModern ? 28 : 24,
      overflow: 'hidden',
      display: 'flex', flexDirection: 'column',
      fontFamily: 'Roboto, sans-serif', color: palette.fg,
      boxShadow: '0 20px 60px rgba(0,0,0,0.4)',
      position: 'relative',
    }}>
      <StatusBar api={generation === 'modern' ? 'modern' : 'baseline'} />
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '8px 12px' }}>
        <div style={{ width: 40, height: 40, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="chevron_down" size={24} color={palette.fg} />
        </div>
        <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: '0.08em', color: palette.fgSec, textTransform: 'uppercase' }}>Playing from playlist</div>
        <div style={{ width: 40, height: 40, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="more_vert" size={22} color={palette.fg} />
        </div>
      </div>

      {/* Artwork */}
      <div style={{ padding: '8px 28px 0', flex: 1, display: 'flex', alignItems: 'center' }}>
        <div style={{
          width: '100%', aspectRatio: '1',
          borderRadius: isYou ? 28 : isModern ? 20 : 14,
          background: `linear-gradient(135deg, ${palette.accent} 0%, ${palette.accentDim} 100%)`,
          boxShadow: '0 16px 48px rgba(0,0,0,0.7)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <div style={{ width: 92, height: 92, borderRadius: '50%', border: `3px solid rgba(255,255,255,0.18)`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <div style={{ width: 22, height: 22, borderRadius: '50%', background: 'rgba(255,255,255,0.25)' }} />
          </div>
        </div>
      </div>

      {/* Meta */}
      <div style={{ padding: '20px 24px 8px', display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 22, fontWeight: 600, letterSpacing: '-0.02em', lineHeight: 1.2, marginBottom: 4 }}>Bohemian Rhapsody</div>
          <div style={{ fontSize: 14, color: palette.fgSec }}>Queen Official</div>
        </div>
        <div style={{ width: 40, height: 40, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="favorite" size={22} color={palette.accent} />
        </div>
      </div>

      {/* Scrubber */}
      <div style={{ padding: '0 24px 12px' }}>
        <div style={{ height: isYou ? 6 : 4, background: palette.surfaceVar, borderRadius: 999, position: 'relative' }}>
          <div style={{ height: '100%', width: '42%', background: palette.accent, borderRadius: 999, position: 'relative' }}>
            <div style={{ position: 'absolute', right: -7, top: '50%', transform: 'translateY(-50%)', width: isYou ? 18 : 14, height: isYou ? 18 : 14, background: palette.accent, borderRadius: '50%', boxShadow: '0 2px 6px rgba(0,0,0,0.4)' }} />
          </div>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 6, fontSize: 11, color: palette.fgTer, fontVariantNumeric: 'tabular-nums' }}>
          <span>2:28</span><span>5:54</span>
        </div>
      </div>

      {/* Transport */}
      <div style={{ padding: '8px 24px 16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="shuffle" size={20} color={palette.fgSec} />
        </div>
        <div style={{ width: 48, height: 48, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="skip_prev" size={28} color={palette.fg} />
        </div>
        {/* Center play button — shape-morph in modern, circle in baseline, squircle in you */}
        <div style={{
          width: 68, height: 68,
          borderRadius: isModern ? 22 : isYou ? 24 : '50%',
          background: palette.accent,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: `0 6px 24px ${palette.accent}55`,
        }}>
          <Icon name="pause" size={28} color="#1A1014" />
        </div>
        <div style={{ width: 48, height: 48, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="skip_next" size={28} color={palette.fg} />
        </div>
        <div style={{ width: 44, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Icon name="repeat" size={20} color={palette.fgSec} />
        </div>
      </div>

      {/* Bottom utility row */}
      <div style={{ padding: '0 24px 20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', color: palette.fgSec, fontSize: 12 }}>
        <Icon name="cast" size={18} color={palette.fgSec} strokeWidth={1.8} />
        <Icon name="queue" size={18} color={palette.fgSec} />
      </div>
      <NavBar generation={generation === 'modern' ? 'modern' : 'baseline'} tone="dark" />
    </div>
  );
};

// LIBRARY screen
const PhoneLibrary = ({ generation }) => {
  const palette = PALETTES[generation];
  const isYou = generation === 'you';
  const isModern = generation === 'modern';
  return (
    <div style={{
      width: 360, height: 720,
      background: palette.bg,
      borderRadius: isModern ? 28 : 24,
      overflow: 'hidden',
      display: 'flex', flexDirection: 'column',
      fontFamily: 'Roboto, sans-serif', color: palette.fg,
      boxShadow: '0 20px 60px rgba(0,0,0,0.4)',
      position: 'relative',
    }}>
      <StatusBar api={generation === 'modern' ? 'modern' : 'baseline'} />
      <M3TopAppBar
        title="Library"
        generation={generation}
        palette={palette}
        trailing={<>
          <div style={{ width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Icon name="search" size={20} color={palette.fgSec} strokeWidth={1.8} /></div>
        </>}
      />

      {/* Filter chips */}
      <div style={{ display: 'flex', gap: 8, padding: '0 16px 14px' }}>
        {['Playlists', 'Artists', 'Downloaded'].map((c, i) => (
          <div key={c} style={{
            padding: '6px 14px',
            background: i === 0 ? palette.accentSurface : 'transparent',
            border: `1px solid ${i === 0 ? palette.accent : palette.border}`,
            color: i === 0 ? palette.accent : palette.fg,
            borderRadius: 999, fontSize: 12, fontWeight: 500,
          }}>{c}</div>
        ))}
      </div>

      {/* Playlist rows */}
      <div style={{ flex: 1, overflow: 'hidden' }}>
        {SAMPLE_PLAYLISTS.map((pl, i) => (
          <div key={pl.id} style={{
            display: 'flex', alignItems: 'center', gap: 14,
            padding: '8px 16px', height: 72,
            borderTop: i === 0 ? 'none' : `1px solid ${palette.border}`,
          }}>
            <PlaylistCover colors={pl.colors} size={56} radius={isYou ? 14 : 8} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 15, fontWeight: 500, color: palette.fg, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{pl.name}</div>
              <div style={{ fontSize: 12, color: palette.fgSec, marginTop: 2 }}>Playlist · {pl.trackCount} tracks</div>
            </div>
            <Icon name="more_vert" size={18} color={palette.fgTer} />
          </div>
        ))}
      </div>

      {/* FAB */}
      <div style={{ position: 'absolute', right: 16, bottom: 168, zIndex: 3 }}>
        <div style={{
          width: 56, height: 56,
          borderRadius: isYou ? 18 : isModern ? 20 : 16,
          background: palette.accent,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: `0 8px 24px ${palette.accent}55`,
        }}>
          <Icon name="add" size={26} color="#1A1014" />
        </div>
      </div>

      <PhoneMiniPlayer generation={generation} palette={palette} />
      <NavTabM3 generation={generation} palette={palette} active="library" />
      <NavBar generation={generation === 'modern' ? 'modern' : 'baseline'} tone="dark" />
    </div>
  );
};

Object.assign(window, { PALETTES, PhoneHome, PhoneNowPlaying, PhoneLibrary });
