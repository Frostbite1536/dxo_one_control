import fs from 'fs';
import path from 'path';
import { execFileSync } from 'child_process';

const [_NODEBIN, _SCRIPT, dngPath] = process.argv;

const ABOUT_MSG = `
---
USAGE: node ${_SCRIPT} <path to DNG directory>

Resizes and converts DNGs to JPGs without modifying the image's colorspace.
Onboard DXO One firmware alters this colorspace during conversion from DNG to JPG!
---
`.trim();

const FACTOR = 0.25;
const RESIZED_H = 3688 * FACTOR;
const RESIZED_W = 5540 * FACTOR;
const RESIZED_DIR = 'resized';

function batchResize() {
    if (!dngPath) {
        console.log(ABOUT_MSG);
        return;
    }

    const files = fs.readdirSync(dngPath);
    const outDir = path.resolve(dngPath, RESIZED_DIR);
    fs.mkdirSync(outDir, { recursive: true });

    for (const f of files) {
        if (path.extname(f).toLowerCase() !== '.dng') continue;

        // resolve() yields an absolute path, so a file named "-x.dng" can't be
        // mistaken for a sips option.
        const input = path.resolve(dngPath, f);
        const output = path.join(outDir, `${path.parse(f).name}.jpg`);

        // Arguments are passed directly to sips (no shell), so filenames
        // containing spaces or shell metacharacters are handled safely.
        try {
            execFileSync('sips', [
                '-s', 'format', 'jpeg',
                input,
                '--resampleHeightWidth', String(RESIZED_H), String(RESIZED_W),
                '--out', output,
            ], { stdio: ['ignore', 'ignore', 'pipe'] });
        } catch (e) {
            const detail = e.stderr ? e.stderr.toString('utf8').trim() : e.message;
            console.error(`Failed to convert ${f}: ${detail}`);
            process.exitCode = 1;
        }
    }
}

batchResize();