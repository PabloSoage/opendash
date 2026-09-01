#!/usr/bin/env node
// Balance check for Kotlin sources.
//
// Not a parser and not a substitute for one: this repository is developed on a
// machine with no Kotlin toolchain, so the build happens in CI and a stray
// brace costs a round trip. This catches the one class of mistake that a
// careful read misses, before pushing.
//
//   node tools/check-braces.mjs [paths...]
//
// With no arguments it walks every .kt under android/.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

function sources(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) sources(path, out);
    else if (name.endsWith('.kt')) out.push(path);
  }
  return out;
}

const QUOTE = '"';
const BACKSLASH = '\\';

// Walks the text once, tracking whether it is inside a string or a comment, so
// a brace inside either does not count. Raw strings are handled because Kotlin
// uses three quotes for them and they cannot nest.
function balance(text) {
  let braces = 0, parens = 0;
  let inString = false, inRaw = false, inBlock = false, inLine = false, escaped = false;
  let inChar = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    const next = text[i + 1];
    // Character literals first, and they matter: a Kotlin source that trims a
    // quote writes '"', and a checker that misses it reads the rest of the file
    // as one long string.
    if (inChar) {
      if (escaped) escaped = false;
      else if (c === BACKSLASH) escaped = true;
      else if (c === "'") inChar = false;
      continue;
    }
    if (inLine) {
      if (c === '\n') inLine = false;
      continue;
    }
    if (inBlock) {
      if (c === '*' && next === '/') { inBlock = false; i++; }
      continue;
    }
    if (inRaw) {
      if (c === QUOTE && next === QUOTE && text[i + 2] === QUOTE) { inRaw = false; i += 2; }
      continue;
    }
    if (inString) {
      if (escaped) { escaped = false; continue; }
      if (c === BACKSLASH) { escaped = true; continue; }
      if (c === QUOTE) inString = false;
      continue;
    }
    if (c === '/' && next === '/') { inLine = true; i++; continue; }
    if (c === '/' && next === '*') { inBlock = true; i++; continue; }
    if (c === QUOTE && next === QUOTE && text[i + 2] === QUOTE) { inRaw = true; i += 2; continue; }
    if (c === QUOTE) { inString = true; continue; }
    if (c === "'") { inChar = true; escaped = false; continue; }
    if (c === '{') braces++;
    else if (c === '}') braces--;
    else if (c === '(') parens++;
    else if (c === ')') parens--;
    if (braces < 0 || parens < 0) return { braces, parens, early: true };
  }
  return { braces, parens, early: false };
}

const paths = process.argv.length > 2 ? process.argv.slice(2) : sources('android/src/main/java');
let bad = 0;
for (const path of paths) {
  const r = balance(readFileSync(path, 'utf8'));
  const ok = r.braces === 0 && r.parens === 0;
  if (!ok) {
    bad++;
    console.log(`${path}: braces ${r.braces >= 0 ? '+' : ''}${r.braces}, parens ${r.parens >= 0 ? '+' : ''}${r.parens}` +
      (r.early ? '  (closed one that was never opened)' : ''));
  }
}
console.log(bad === 0 ? `balanced: ${paths.length} files` : `${bad} file(s) unbalanced`);
process.exit(bad === 0 ? 0 : 1);
