#!/usr/bin/env node
//
// Checks the string resources against the code, without a compiler.
//
// Three ways a translated app rots, all of them silent at build time on a
// release build and all of them caught here:
//
//   1. Code references a string that no locale defines. On Android this is a
//      compile error for the default locale but a runtime crash if only a
//      translation is missing, which nobody notices until they switch language.
//   2. A locale is missing a key the default has, so that screen falls back to
//      English mid-sentence.
//   3. Format placeholders disagree between locales — %1$d in one, %1$s in
//      another — which throws when the string is formatted.
//
// Exits non-zero with a list. No dependencies; run it with `node`.

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const res = join(root, 'android', 'src', 'main', 'res')
const src = join(root, 'android', 'src', 'main', 'java')

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) walk(path, out)
    else out.push(path)
  }
  return out
}

/** name -> raw value, for one values* directory. */
function strings(directory) {
  const path = join(res, directory, 'strings.xml')
  const xml = readFileSync(path, 'utf8')
  const found = new Map()
  for (const m of xml.matchAll(/<string name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)) {
    found.set(m[1], m[2])
  }
  return found
}

/** The %1$s / %2$d markers a value uses, sorted, as a comparable signature. */
function placeholders(value) {
  return [...value.matchAll(/%(\d+)\$([a-zA-Z])/g)]
    .map((m) => m[1] + m[2])
    .sort()
    .join(',')
}

const locales = readdirSync(res).filter(
  (d) => d === 'values' || (d.startsWith('values-') && !d.includes('-v') && !d.includes('night')),
)

const base = strings('values')
const problems = []

// 1. every R.string.x in the Kotlin sources exists
const used = new Set()
for (const file of walk(src).filter((f) => f.endsWith('.kt'))) {
  const text = readFileSync(file, 'utf8')
  for (const m of text.matchAll(/R\.string\.([a-z0-9_]+)/g)) used.add(m[1])
}
for (const name of [...used].sort()) {
  if (!base.has(name)) problems.push(`used in code but not defined: ${name}`)
}
for (const name of [...base.keys()].sort()) {
  if (!used.has(name)) problems.push(`defined but never used: ${name}`)
}

// 2 and 3. every other locale matches the default, key for key
for (const locale of locales.filter((l) => l !== 'values')) {
  const other = strings(locale)
  for (const name of base.keys()) {
    if (!other.has(name)) {
      problems.push(`${locale}: missing ${name}`)
      continue
    }
    const a = placeholders(base.get(name))
    const b = placeholders(other.get(name))
    if (a !== b) problems.push(`${locale}: ${name} has placeholders [${b}], default has [${a}]`)
  }
  for (const name of other.keys()) {
    if (!base.has(name)) problems.push(`${locale}: ${name} is not in the default locale`)
  }
}

if (problems.length) {
  console.error(problems.join('\n'))
  console.error(`\n${problems.length} problem(s)`)
  process.exit(1)
}
console.log(
  `resources ok: ${base.size} strings, locales ${locales.join(' ')}, ${used.size} referenced in code`,
)
