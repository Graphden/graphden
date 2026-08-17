// Graphden EDN runtime — a minimal hiccup-oriented EDN reader/printer for
// value-form controls with `data-field-kind="edn"` (graphden-forms.js reads
// them through parseEdn / printEdn). Platform-shared: bundled into BOTH the
// editor and the standalone /assets/graphden-runtime.js, like the rest of
// web/runtime/.
//
// The stored / transit form of a hiccup value is JSON-shaped (string tags,
// string attr keys — see `:hiccup-normalize` in web/html): keywords cannot
// survive the JSONB round trip. This module is the presentation layer over
// that convention:
//   parseEdn  — full EDN syntax for authoring (`[:div {:class "x"} "hi"]`);
//               every keyword normalizes to its name string ("div", "class"),
//               which IS the canonical stored form.
//   printEdn  — the inverse sugar: a string in tag position (element 0 of a
//               vector) or map-key position prints as a keyword when it is a
//               valid keyword name, so the round trip shows the same shape
//               the docs and tutorials write. Value-lossless: reparsing the
//               printed text yields the identical stored value.
//
// Not supported (clear error instead of silent surprise): sets, tagged
// literals, char literals, symbols, auto-resolved (::) keywords, bigint /
// bigdec suffixes.

// ============================================================================
// READER
// ============================================================================

// Parse an EDN string into the JSON-shaped stored value. Throws an Error
// with a human-readable message (including line:col) on bad syntax.
function parseEdn(text) {
  const s = String(text);
  let pos = 0;

  function fail(msg, at) {
    const p = at === undefined ? pos : at;
    let line = 1;
    let col = 1;
    for (let i = 0; i < p && i < s.length; i++) {
      if (s[i] === '\n') { line++; col = 1; } else col++;
    }
    throw new Error(msg + ' (line ' + line + ':' + col + ')');
  }

  function skipWs() {
    for (;;) {
      while (pos < s.length && (/\s/.test(s[pos]) || s[pos] === ',')) pos++;
      if (s[pos] === ';') {
        while (pos < s.length && s[pos] !== '\n') pos++;
        continue;
      }
      return;
    }
  }

  function readDelimited(close) {
    const out = [];
    for (;;) {
      skipWs();
      if (pos >= s.length) fail("missing closing '" + close + "'");
      if (s[pos] === close) { pos++; return out; }
      out.push(readValue());
    }
  }

  function readMap() {
    const entries = readDelimited('}');
    if (entries.length % 2 !== 0) fail('map has a key with no value');
    const obj = {};
    for (let i = 0; i < entries.length; i += 2) {
      const k = entries[i];
      if (typeof k !== 'string') {
        fail('map keys must be keywords or strings, got ' + JSON.stringify(k));
      }
      obj[k] = entries[i + 1];
    }
    return obj;
  }

  function readString() {
    pos++; // opening quote
    let out = '';
    for (;;) {
      if (pos >= s.length) fail('unterminated string');
      const c = s[pos];
      if (c === '"') { pos++; return out; }
      if (c === '\\') {
        const e = s[pos + 1];
        if (e === 'n') out += '\n';
        else if (e === 't') out += '\t';
        else if (e === 'r') out += '\r';
        else if (e === '"') out += '"';
        else if (e === '\\') out += '\\';
        else if (e === 'u') {
          const hex = s.slice(pos + 2, pos + 6);
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) fail('bad \\u escape');
          out += String.fromCharCode(parseInt(hex, 16));
          pos += 4;
        } else fail("unsupported escape '\\" + (e || '') + "'");
        pos += 2;
        continue;
      }
      out += c;
      pos++;
    }
  }

  function readToken() {
    const start = pos;
    while (pos < s.length && !/[\s,()[\]{}"';]/.test(s[pos])) pos++;
    if (pos === start) fail("unexpected character '" + s[pos] + "'");
    return s.slice(start, pos);
  }

  function readValue() {
    skipWs();
    if (pos >= s.length) fail('unexpected end of input');
    const c = s[pos];
    if (c === '[') { pos++; return readDelimited(']'); }
    if (c === '(') { pos++; return readDelimited(')'); }
    if (c === '{') { pos++; return readMap(); }
    if (c === '"') return readString();
    if (c === ')' || c === ']' || c === '}') fail("unexpected '" + c + "'");
    if (c === '#') fail('sets / tagged literals are not supported here');
    if (c === '\\') fail('char literals are not supported here — use a string');
    if (c === ':') {
      if (s[pos + 1] === ':') fail('auto-resolved (::) keywords are not supported');
      pos++;
      const name = readToken();
      // A keyword IS its name string in the stored form (string tags /
      // attr keys — the `:hiccup-normalize` convention).
      return name;
    }
    const at = pos;
    const tok = readToken();
    if (tok === 'nil') return null;
    if (tok === 'true') return true;
    if (tok === 'false') return false;
    if (/^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(tok)) return Number(tok);
    if (/^[-+]?\d/.test(tok)) fail("bad number '" + tok + "'", at);
    fail("unexpected symbol '" + tok + "' — text needs \"quotes\"", at);
    return undefined; // unreachable — fail always throws
  }

  const value = readValue();
  skipWs();
  if (pos < s.length) fail('unexpected trailing content');
  return value;
}

// ============================================================================
// PRINTER
// ============================================================================

// A string printable as a bare keyword (`:div`) — conservative EDN
// keyword-name subset, at most one namespace slash.
const EDN_KEYWORD_NAME =
  /^[a-zA-Z_*+!?<>=-][a-zA-Z0-9_*+!?<>=.-]*(\/[a-zA-Z0-9_*+!?<>=.-]+)?$/;

function ednKeywordable(v) {
  return typeof v === 'string' && EDN_KEYWORD_NAME.test(v);
}

function printEdnAtom(v, asKeyword) {
  if (v === null || v === undefined) return 'nil';
  if (typeof v === 'string') {
    return asKeyword && ednKeywordable(v) ? ':' + v : JSON.stringify(v);
  }
  return String(v); // number | boolean
}

// Print the stored (JSON-shaped) value as pretty EDN. `indent` is the column
// the current value starts at; continuation lines align lisp-style (children
// one column past the opening bracket). `asKeyword` applies the tag-position
// sugar to a string value.
function printEdnAt(v, indent, asKeyword) {
  if (v === null || v === undefined || typeof v !== 'object') {
    return printEdnAtom(v, asKeyword);
  }
  const parts = [];
  let open;
  let close;
  if (Array.isArray(v)) {
    open = '[';
    close = ']';
    for (let i = 0; i < v.length; i++) {
      // Element 0 of a vector is the tag position in hiccup.
      parts.push({ v: v[i], kw: i === 0 });
    }
  } else {
    open = '{';
    close = '}';
    for (const k of Object.keys(v)) {
      parts.push({ v: k, kw: true });
      parts.push({ v: v[k], kw: false });
    }
  }
  const inline = open
    + parts.map((p) => printEdnAt(p.v, 0, p.kw)).join(' ')
    + close;
  if (indent + inline.length <= 72 || parts.length === 0) return inline;
  // Multi-line: maps break per key-value PAIR, vectors per element; the
  // first line keeps the opener (and for vectors the tag + attrs feel comes
  // free because short heads stay inline above).
  const childIndent = indent + 1;
  const pad = '\n' + ' '.repeat(childIndent);
  const lines = [];
  if (Array.isArray(v)) {
    for (const p of parts) lines.push(printEdnAt(p.v, childIndent, p.kw));
    // Hiccup style: keep `[:tag {attrs}` together on the opening line when
    // both are short single-liners.
    if (lines.length > 1 && !lines[0].includes('\n') && !lines[1].includes('\n')
        && childIndent + lines[0].length + 1 + lines[1].length <= 72) {
      lines.splice(0, 2, lines[0] + ' ' + lines[1]);
    }
  } else {
    for (let i = 0; i < parts.length; i += 2) {
      const key = printEdnAt(parts[i].v, childIndent, true);
      lines.push(key + ' '
        + printEdnAt(parts[i + 1].v, childIndent + key.length + 1, false));
    }
  }
  return open + lines.join(pad) + close;
}

function printEdn(v) {
  return printEdnAt(v, 0, false);
}
