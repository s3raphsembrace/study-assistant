/**
 * Helpers for the browser's built-in speech synthesis (Web Speech API).
 * Nothing here touches the network: voices come from the operating system.
 */

/** Split text into sentence-sized utterances. Short pieces avoid Chrome's
 *  habit of cutting off long utterances after ~15 seconds. */
export function splitSentences(text: string): string[] {
  const out: string[] = [];
  for (const para of text.split(/\n{2,}/)) {
    const p = para.replace(/\s+/g, ' ').trim();
    if (!p) continue;
    // Break after . ! ? (optionally followed by a closing quote/paren) when a
    // space and a new sentence follow. Keep decimals like "3.14" together.
    const parts = p.split(/(?<=[.!?][)"”']?)\s+(?=[A-Z0-9("“])/);
    for (const s of parts) {
      const t = s.trim();
      if (!t) continue;
      // Very long sentences (lists joined by commas) still get chunked.
      if (t.length > 320) {
        let buf = '';
        for (const piece of t.split(/(?<=[,;:])\s+/)) {
          if (buf && buf.length + piece.length > 240) {
            out.push(buf);
            buf = '';
          }
          buf = buf ? `${buf} ${piece}` : piece;
        }
        if (buf) out.push(buf);
      } else {
        out.push(t);
      }
    }
  }
  return out;
}

/**
 * Reduce Markdown notes to readable prose: drop syntax, turn headings into
 * short sentences, flatten lists and tables. Used when no spoken version has
 * been generated yet.
 */
export function markdownToSpeech(md: string): string {
  let t = md.replace(/\r\n/g, '\n');
  t = t.replace(/```[\s\S]*?```/g, ' (code omitted) ');
  t = t.replace(/\$\$([\s\S]*?)\$\$/g, (_, m) => ` ${latexToWords(m)} `);
  t = t.replace(/\$([^$\n]+?)\$/g, (_, m) => ` ${latexToWords(m)} `);
  t = t.replace(/!\[[^\]]*\]\([^)]*\)/g, '');
  t = t.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1');
  // Bare "#" lines (a model hiccup) are dropped; real headings become short sentences.
  // Only horizontal whitespace inside the pattern, so it can never span lines.
  t = t.replace(/^[ \t]{0,3}#{1,6}[ \t]*$/gm, '');
  t = t.replace(/^[ \t]{0,3}#{1,6}[ \t]+(.+?)[ \t]*#*[ \t]*$/gm, (_, h) => `\n\n${h.trim().replace(/[.:]$/, '')}.\n\n`);
  t = t.replace(/^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/gm, '');
  t = t.replace(/^\s*\|(.+)\|\s*$/gm, (_, row: string) =>
    row.split('|').map((c) => c.trim()).filter(Boolean).join(', ') + '.',
  );
  t = t.replace(/^\s*>\s?/gm, '');
  t = t.replace(/^\s*[-*+]\s+/gm, '');
  t = t.replace(/^\s*\d+\.\s+/gm, '');
  t = t.replace(/(\*\*|__)(.+?)\1/g, '$2');
  t = t.replace(/(\*|_)(.+?)\1/g, '$2');
  t = t.replace(/`([^`]+)`/g, '$1');
  t = t.replace(/^\s*-{3,}\s*$/gm, '');
  t = t.replace(/[ \t]+/g, ' ');
  t = t.replace(/\n{3,}/g, '\n\n');
  return t.trim();
}

/** Very small LaTeX-to-words pass for the common cases in lecture notes. */
export function latexToWords(tex: string): string {
  let s = tex;
  const rules: [RegExp, string][] = [
    [/\\frac\{([^{}]*)\}\{([^{}]*)\}/g, '$1 over $2'],
    [/\\sqrt\{([^{}]*)\}/g, 'square root of $1'],
    [/\\sum/g, 'the sum of'],
    [/\\int/g, 'the integral of'],
    [/\\infty/g, 'infinity'],
    [/\\pi/g, 'pi'],
    [/\\alpha/g, 'alpha'], [/\\beta/g, 'beta'], [/\\gamma/g, 'gamma'], [/\\delta/g, 'delta'],
    [/\\theta/g, 'theta'], [/\\lambda/g, 'lambda'], [/\\mu/g, 'mu'], [/\\sigma/g, 'sigma'],
    [/\\omega/g, 'omega'], [/\\epsilon/g, 'epsilon'], [/\\rho/g, 'rho'], [/\\tau/g, 'tau'],
    [/\\cdot|\\times/g, ' times '],
    [/\\pm/g, ' plus or minus '],
    [/\\leq|<=/g, ' less than or equal to '],
    [/\\geq|>=/g, ' greater than or equal to '],
    [/\\neq|!=/g, ' not equal to '],
    [/\\approx/g, ' approximately '],
    [/\\rightarrow|\\to/g, ' goes to '],
    [/\^\{?2\}?/g, ' squared'],
    [/\^\{?3\}?/g, ' cubed'],
    [/\^\{([^{}]*)\}/g, ' to the power of $1'],
    [/\^(\w)/g, ' to the power of $1'],
    [/_\{([^{}]*)\}/g, ' sub $1'],
    [/_(\w)/g, ' sub $1'],
    [/=/g, ' equals '],
    [/\+/g, ' plus '],
    [/(?<=\S)\s*-\s*(?=\S)/g, ' minus '],
    [/\//g, ' over '],
    [/\\[a-zA-Z]+/g, ' '],
    [/[{}]/g, ' '],
  ];
  for (const [re, rep] of rules) s = s.replace(re, rep);
  return s.replace(/\s+/g, ' ').trim();
}

export interface VoiceOption {
  name: string;
  lang: string;
  local: boolean;
  voice: SpeechSynthesisVoice;
}

/** Voices, resolved once the browser has loaded them (Chrome fires an event). */
export function loadVoices(): Promise<VoiceOption[]> {
  const synth = globalThis.speechSynthesis;
  if (!synth) return Promise.resolve([]);
  const toOptions = (vs: SpeechSynthesisVoice[]) =>
    vs
      .map((voice) => ({ name: voice.name, lang: voice.lang, local: voice.localService, voice }))
      .sort((a, b) => Number(b.local) - Number(a.local) || a.lang.localeCompare(b.lang) || a.name.localeCompare(b.name));
  const now = synth.getVoices();
  if (now.length) return Promise.resolve(toOptions(now));
  return new Promise((resolve) => {
    const done = () => resolve(toOptions(synth.getVoices()));
    synth.addEventListener('voiceschanged', done, { once: true });
    setTimeout(done, 1500); // some browsers never fire the event
  });
}
