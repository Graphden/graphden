// Entry for the vendored CodeMirror 6 bundle (window.CM).
// Build: npm install && npm run build  (from tools/vendor-build/).
// Everything the editor's code-editing surfaces need, one IIFE, no CDN.

import {closeBrackets} from '@codemirror/autocomplete';
import {defaultKeymap, history, historyKeymap, indentWithTab} from '@codemirror/commands';
import {css} from '@codemirror/lang-css';
import {javascript} from '@codemirror/lang-javascript';
import {json} from '@codemirror/lang-json';
import {bracketMatching, defaultHighlightStyle, indentOnInput, syntaxHighlighting} from '@codemirror/language';
import {MergeView} from '@codemirror/merge';
import {highlightSelectionMatches, search, searchKeymap} from '@codemirror/search';
import {EditorState} from '@codemirror/state';
import {EditorView, highlightActiveLine, keymap, lineNumbers} from '@codemirror/view';
import {clojure} from '@nextjournal/lang-clojure';

window.CM = {
  EditorState,
  EditorView,
  MergeView,
  keymap,
  lineNumbers,
  highlightActiveLine,
  highlightSelectionMatches,
  search,
  searchKeymap,
  defaultKeymap,
  historyKeymap,
  history,
  indentWithTab,
  bracketMatching,
  closeBrackets,
  indentOnInput,
  syntaxHighlighting,
  defaultHighlightStyle,
  langs: {javascript, css, json, clojure},
};
