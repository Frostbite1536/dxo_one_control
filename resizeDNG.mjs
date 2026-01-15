import fs from 'fs';
import path from 'path';
import { spawnSync } from 'child_process';

const [_NODEBIN, _SCRIPT, dngPath] = process.argv;

const ABOUT_MSG = `
---
USAGE: node ${_SCRIPT} <path to DNG directory>

Resizes and converts DNGs to JPGs without modifying the image's colorspace.
Onboard DXO One firmware alters this colorspace during conversion from DNG to JPG!
---
`.trim();

const FACTOR = 0.25;
const RESIZED_H = Math.round(3688 * FACTOR);
const RESIZED_W = Math.round(5540 * FACTOR);
const RESIZED_DIR = 'resized';

function batchResize() {
    if (!dngPath) {
        console.log(ABOUT_MSG);
        return;
    }

    // Validate path exists and is a directory
    if (!fs.existsSync(dngPath) || !fs.statSync(dngPath).isDirectory()) {
        console.error(`Error: "${dngPath}" is not a valid directory`);
        return;
    }

    const files = fs.readdirSync(dngPath);
    const outputDir = path.join(dngPath, RESIZED_DIR);
    fs.mkdirSync(outputDir, { recursive: true });

    let processedCount = 0;

    for (const f of files) {
        if (!f.endsWith('.DNG')) continue;

        const inputFile = path.join(dngPath, f);
        const outputFile = path.join(outputDir, f.replace('.DNG', '.jpg'));

        // Bug fix: Use spawnSync with array arguments to prevent command injection
        const result = spawnSync('sips', [
            '-s', 'format', 'jpeg',
            inputFile,
            '--resampleHeightWidth', String(RESIZED_H), String(RESIZED_W),
            '--out', outputFile
        ], { encoding: 'utf8' });

        if (result.status !== 0) {
            console.error(`Error processing ${f}: ${result.stderr}`);
        } else {
            processedCount++;
            console.log(`Processed: ${f}`);
        }
    }

    console.log(`\nCompleted: ${processedCount} files processed`);
}

batchResize();