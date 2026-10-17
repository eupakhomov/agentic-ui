// Renders the raster icon set from frontend/public/favicon.svg and logo.svg.
// Usage: node scripts/gen-icons.mjs   (needs @resvg/resvg-js, a frontend devDependency)
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const { Resvg } = createRequire(join(root, 'frontend', 'package.json'))('@resvg/resvg-js');
const pub = join(root, 'frontend', 'public');
const png = (svgFile, size, pad = 0) => {
  const svg = readFileSync(join(pub, svgFile), 'utf8');
  const r = new Resvg(svg, { fitTo: { mode: 'width', value: Math.round(size * (1 - 2 * pad)) }, background: undefined });
  const inner = r.render();
  if (!pad) return inner.asPng();
  // centre the mark on a square canvas with padding (maskable/apple icons)
  const wrapped = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}"><rect width="100%" height="100%" fill="#14161a"/><image href="data:image/png;base64,${inner.asPng().toString('base64')}" x="${(size - inner.width) / 2}" y="${(size - inner.height) / 2}" width="${inner.width}" height="${inner.height}"/></svg>`;
  return new Resvg(wrapped).render().asPng();
};

writeFileSync(join(pub, 'apple-touch-icon.png'), png('logo.svg', 180, 0.1));
writeFileSync(join(pub, 'icon-192.png'), png('logo.svg', 192, 0.1));
writeFileSync(join(pub, 'icon-512.png'), png('logo.svg', 512, 0.1));

// favicon.ico: PNG-compressed entries at 16/32/48
const sizes = [16, 32, 48];
const imgs = sizes.map((s) => png('favicon.svg', s));
const head = Buffer.alloc(6 + 16 * sizes.length);
head.writeUInt16LE(1, 2); head.writeUInt16LE(sizes.length, 4);
let offset = head.length;
sizes.forEach((s, i) => {
  const o = 6 + 16 * i;
  head[o] = s; head[o + 1] = s; head.writeUInt16LE(1, o + 4); head.writeUInt16LE(32, o + 6);
  head.writeUInt32LE(imgs[i].length, o + 8); head.writeUInt32LE(offset, o + 12);
  offset += imgs[i].length;
});
writeFileSync(join(pub, 'favicon.ico'), Buffer.concat([head, ...imgs]));
console.log('icons written to', pub);
