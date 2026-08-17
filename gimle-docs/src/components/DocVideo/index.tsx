// Video embed for docs pages. Copy to gimle-docs/src/components/DocVideo/index.tsx (once).
//
// Tutorial screencasts: default props — user-initiated playback with controls.
// Short doodle loops: pass `loop autoPlay` — playback is then muted and inline, and
// prefers-reduced-motion users get the poster image instead of a moving loop.
import React from 'react';
import useBaseUrl from '@docusaurus/useBaseUrl';

type Props = {
  /** Path under static/, e.g. "/video/deploy-first-module.webm". */
  src: string;
  /** Poster image path under static/; strongly recommended (also the reduced-motion fallback). */
  poster?: string;
  /** Short figcaption shown under the video. */
  caption?: string;
  /** WebVTT path under static/ for a captions track, e.g. "/video/deploy-first-module.vtt". */
  captions?: string;
  loop?: boolean;
  autoPlay?: boolean;
  width?: number | string;
};

export default function DocVideo({
  src,
  poster,
  caption,
  captions,
  loop = false,
  autoPlay = false,
  width = '100%',
}: Props): React.ReactNode {
  const resolvedSrc = useBaseUrl(src);
  const resolvedPoster = useBaseUrl(poster ?? '');
  const resolvedCaptions = useBaseUrl(captions ?? '');
  const [reducedMotion, setReducedMotion] = React.useState(false);

  React.useEffect(() => {
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    setReducedMotion(query.matches);
    const onChange = (event: MediaQueryListEvent) => setReducedMotion(event.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);

  const posterOnly = autoPlay && reducedMotion && poster !== undefined;

  return (
    <figure style={{margin: '1.5rem 0', textAlign: 'center'}}>
      {posterOnly ? (
        <img
          src={resolvedPoster}
          alt={caption ?? ''}
          style={{width, maxWidth: '100%', borderRadius: 8}}
        />
      ) : (
        <video
          src={resolvedSrc}
          poster={poster !== undefined ? resolvedPoster : undefined}
          // Autoplay is only viable muted+inline; browsers block it otherwise.
          controls={!autoPlay}
          muted={autoPlay}
          playsInline={autoPlay}
          autoPlay={autoPlay}
          loop={loop}
          preload={autoPlay ? 'auto' : 'metadata'}
          style={{width, maxWidth: '100%', borderRadius: 8}}
        >
          {captions !== undefined && (
            <track kind="captions" src={resolvedCaptions} srcLang="en" label="English" default />
          )}
        </video>
      )}
      {caption !== undefined && (
        <figcaption style={{fontSize: '0.85rem', opacity: 0.75, marginTop: '0.5rem'}}>
          {caption}
        </figcaption>
      )}
    </figure>
  );
}
