// Generates app/src/test/resources/sf/curve-reference.json from Martin's original userscript,
// so the Java port (ToneCurve, GradingLut) is tested against the real JavaScript, not a copy.
//
// Usage: node tools/sf/reference/make-curve-fixture.cjs [path/to/game-curves.js]
// Default source: I:/RetroGames/GAMES_DEAR_TO_ME/WASM/simpsons-hit-and-run-local/assets/game-curves.js
// (curveFunction/validateCurve are identical to discord-stream-filter.user.js v0.2.0).
//
// The extraction mirrors discord-stream-filter/curves.test.cjs: run the source between
// "const KEY =" and "const specs =" in a sandbox and take curveFunction/validateCurve.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const DEFAULT_SOURCE = 'I:/RetroGames/GAMES_DEAR_TO_ME/WASM/simpsons-hit-and-run-local/assets/game-curves.js';
const sourcePath = process.argv[2] || DEFAULT_SOURCE;
const source = fs.readFileSync(sourcePath, 'utf8');
const start = source.indexOf('  const KEY =');
const end = source.indexOf('  const specs =');
if (start < 0 || end < 0) throw new Error(`Unexpected script layout: ${sourcePath}`);
const sandbox = { localStorage: { getItem: () => null } };
vm.runInNewContext(`${source.slice(start, end)}\nglobalThis.helpers = { curveFunction, validateCurve };`, sandbox);
const { curveFunction, validateCurve } = sandbox.helpers;

const id = [[0, 0], [1, 1]];
const preset = (name, o) => ({
  name, brightness: 100, contrast: 100, saturation: 100, gamma: 1,
  curves: { master: id, r: id, g: id, b: id }, ...o,
  curves: { master: id, r: id, g: id, b: id, ...(o.curves || {}) },
});

// Built-ins of the fork (keep in sync with BuiltinPresets.java) plus edge cases.
const presets = [
  preset('neutral', {}),
  preset('film-game', { gamma: 1.25, contrast: 105 }),
  preset('lift-shadows', { gamma: 1.6, contrast: 105 }),
  preset('paper-white', { curves: { master: [[0, 0], [0.10, 0], [0.88, 1], [1, 1]] } }),
  preset('high-contrast', { curves: { master: [[0, 0], [0.25, 0.12], [0.75, 0.88], [1, 1]] } }),
  preset('threshold', { curves: { master: [[0, 0], [0.40, 0.03], [0.60, 0.97], [1, 1]] } }),
  preset('inverted', { curves: { master: [[0, 1], [1, 0]] } }),
  preset('extremes-low', { brightness: 50, contrast: 50, gamma: 0.4 }),
  preset('extremes-high', { brightness: 200, contrast: 200, gamma: 3 }),
  preset('rgb-split', {
    gamma: 1.1, brightness: 110, contrast: 95,
    curves: {
      master: [[0, 0], [0.5, 0.75], [1, 1]],
      r: [[0, 0], [1, 0.8]],
      g: [[0, 0.1], [0.25, 0.45], [0.75, 0.6], [1, 0.9]],
      b: [[0, 1], [0.2, 0], [0.7, 0.9], [1, 0.1]],
    },
  }),
  preset('dense-points', {
    curves: { master: [[0, 0], [0.015, 0.9], [0.5, 0.2], [0.98, 0.9], [1, 1]] },
  }),
];

const clamp01 = v => Math.max(0, Math.min(1, v));
// Direct evaluation: gamma -> master -> channel -> brightness -> contrast, clamping each step
// (the per-channel part of the userscript filter chain; saturation is a separate step).
function channelFunction(p, key) {
  const master = curveFunction(validateCurve(p.curves.master));
  const channel = curveFunction(validateCurve(p.curves[key]));
  return x => {
    let v = clamp01(x);
    if (Math.abs(p.gamma - 1) > 1e-9) v = Math.pow(v, 1 / p.gamma);
    v = channel(master(v));
    v = clamp01(v * p.brightness / 100);
    v = clamp01((v - 0.5) * p.contrast / 100 + 0.5);
    return v;
  };
}
// What the browser does: the SVG table (256 samples of channel(master(i/255))) with linear
// interpolation (feComponentTransfer type="table"). Only reported, not used by the fork.
function browserTableFunction(p, key) {
  const master = curveFunction(validateCurve(p.curves.master));
  const channel = curveFunction(validateCurve(p.curves[key]));
  const table = Array.from({ length: 256 }, (_, i) => channel(master(i / 255)));
  return x => {
    let v = clamp01(x);
    if (Math.abs(p.gamma - 1) > 1e-9) v = Math.pow(v, 1 / p.gamma);
    const k = Math.min(254, Math.floor(v * 255));
    v = table[k] + (v * 255 - k) * (table[k + 1] - table[k]);
    v = clamp01(v * p.brightness / 100);
    v = clamp01((v - 0.5) * p.contrast / 100 + 0.5);
    return v;
  };
}

const out = { source: path.basename(sourcePath), generated: 'tools/sf/reference/make-curve-fixture.cjs', presets: [] };
let worstBrowserDeviation = 0;
for (const p of presets) {
  const entry = { preset: p, lut: {}, curveSamples: {} };
  for (const key of ['r', 'g', 'b']) {
    const f = channelFunction(p, key);
    const t = browserTableFunction(p, key);
    entry.lut[key] = Array.from({ length: 256 }, (_, i) => f(i / 255));
    for (let i = 0; i <= 1000; i++) {
      worstBrowserDeviation = Math.max(worstBrowserDeviation, Math.abs(f(i / 1000) - t(i / 1000)));
    }
  }
  for (const key of ['master', 'r', 'g', 'b']) {
    const c = curveFunction(validateCurve(p.curves[key]));
    entry.curveSamples[key] = Array.from({ length: 101 }, (_, i) => c(i / 100));
  }
  out.presets.push(entry);
}
out.validation = [
  null, [], [[0, 0]], [[0, 0], [0, 1]], [[0.1, 0], [1, 1]], [[0, 0], [0.5, 2], [1, 1]],
  [[0, 0], [0.01, 0.5], [1, 1]], [[0, 0], [0.5, 0.5], [0.4, 0.6], [1, 1]],
].map(points => ({ points, result: validateCurve(points) }));
out.worstBrowserTableDeviation = worstBrowserDeviation;

const target = path.join(__dirname, '..', '..', '..', 'app', 'src', 'test', 'resources', 'sf', 'curve-reference.json');
fs.mkdirSync(path.dirname(target), { recursive: true });
fs.writeFileSync(target, JSON.stringify(out));
console.log(`Wrote ${target}: ${out.presets.length} presets; worst deviation direct vs. browser table = ${worstBrowserDeviation.toExponential(3)}`);
