#!/usr/bin/env bb
;; devtour — the developer code-tour generator + validator.
;;
;; Source of truth: docs/devtour/tour.edn  (blocks -> ordered steps).
;; Each step anchors on a SYMBOL (ns + defn name), never a line number, so
;; the tour survives edits above it. The generator pulls the anchored form's
;; ACTUAL source out of the file at build time and bakes it into a single
;; self-contained docs/devtour/index.html (no server, opens from file://).
;;
;;   bb devtour        -> regenerate docs/devtour/index.html from tour.edn
;;   bb devtour-check  -> (CI) every anchor still resolves uniquely AND the
;;                        committed index.html matches a fresh regeneration.
;;                        Fails loudly if source drifted from the baked tour.
;;
;; Because check regenerates and byte-compares, a rename/removal of a toured
;; form, or any edit to its body, turns CI red until someone re-runs `bb devtour`
;; and commits — the tour cannot silently point at code that no longer exists.

(ns devtour
  (:require
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [clojure.string :as str]
    [rewrite-clj.zip :as z]))


;; Both paths are env-overridable so an external repo can bake an alternate
;; tour (same anchors, different prose) against this checkout without forking
;; the generator. Defaults — and `bb devtour` / `bb devtour-check` — unchanged.
(def ^:private tour-edn (or (System/getenv "DEVTOUR_TOUR") "docs/devtour/tour.edn"))
(def ^:private out-html (or (System/getenv "DEVTOUR_OUT") "docs/devtour/index.html"))


;; --- anchor resolution -----------------------------------------------------

(def ^:private def-heads
  "Top-level forms whose second token names them."
  '#{def defn defn- defmacro defmethod defmulti defonce
     defprotocol deftype defrecord defbase deftest})


(defn- ns->path
  "graphden.executor.interface -> src/graphden/executor/interface.clj
   (Clojure munging: dot->slash, hyphen->underscore.)"
  [ns-sym]
  (str "src/"
       (-> (name ns-sym)
           (str/replace "-" "_")
           (str/replace "." "/"))
       ".clj"))


(defn- safe-sexpr
  "z/sexpr but nil instead of throwing on unreadable nodes (reader tags etc.)."
  [zloc]
  (try (z/sexpr zloc) (catch Exception _ nil)))


(defn- form-signature
  "For a top-level def-form list zloc, return {:name <sym> :dispatch <val>},
   else nil. `:dispatch` is the defmethod dispatch value (3rd token) or ::none
   for ordinary def-forms — so a defmethod can be anchored by (name, dispatch)."
  [zloc]
  (when (= :list (z/tag zloc))
    (let [head (some-> zloc z/down)
          head-sym (safe-sexpr head)]
      (when (contains? def-heads head-sym)
        (let [nm (safe-sexpr (z/right head))]
          (when (symbol? nm)
            (if (= 'defmethod head-sym)
              {:name nm :dispatch (safe-sexpr (-> head z/right z/right))}
              {:name nm :dispatch ::none})))))))


(defn- find-form
  "Locate the def-form named `sym` (and, for a defmethod, dispatching on
   `dispatch`) among the top-level forms of `src`. Returns {:code :line} or
   throws on 0 / >1 matches. A nil `dispatch` matches on name alone."
  [src sym dispatch file]
  (loop [zloc (z/of-string src {:track-position? true})
         hits []]
    (if (or (nil? zloc) (z/end? zloc))
      (case (count hits)
        1 (first hits)
        0 (throw (ex-info (str "anchor not found: " sym
                               (when dispatch (str " / " dispatch)) " in " file)
                          {:sym sym :file file}))
        (throw (ex-info (str "anchor ambiguous: " sym
                             (when dispatch (str " / " dispatch)) " appears "
                             (count hits) "x in " file
                             " — add :dispatch, split the form, or rename")
                        {:sym sym :file file :count (count hits)})))
      (let [sig (form-signature zloc)
            hit (when (and sig
                           (= sym (:name sig))
                           (or (nil? dispatch) (= dispatch (:dispatch sig))))
                  {:code (z/string zloc)
                   :line (first (z/position zloc))})]
        (recur (z/right zloc) (cond-> hits hit (conj hit)))))))


;; --- JS anchor resolution --------------------------------------------------
;;
;; The editor frontend is ~25k lines of plain ES modules concatenated into one
;; bundle — no build step, no imports. It is half the product a newcomer
;; touches, so it is toured too, with the same symbol-anchored contract as the
;; Clojure side: name a declaration, the generator bakes its real source.

(defn- js-decl-re
  "Regex matching the line that DECLARES `sym` in a JS module: a function
   declaration or a const/let/var binding, at any indentation (several
   editor modules wrap their body in an IIFE)."
  [sym]
  (let [n (java.util.regex.Pattern/quote (str sym))]
    (re-pattern (str "^[ \\t]*(?:(?:async[ \\t]+)?function[ \\t]+" n "[ \\t]*\\("
                     "|(?:const|let|var)[ \\t]+" n "[ \\t]*=)"))))


(defn- regex-position?
  "Heuristic: at `i` (a `/` in code state), does a REGEX literal start here
   rather than a division? True when the previous significant character can
   only precede an expression."
  [^String src ^long i]
  (let [prev (loop [j (dec i)]
               (cond (neg? j) nil
                     (Character/isWhitespace (String/.charAt src j)) (recur (dec j))
                     :else (String/.charAt src j)))]
    (or (nil? prev) (contains? (set "(,=:[!&|?{};+-*%^~<>") prev))))


(defn- js-form-end
  "Index (exclusive) of the end of the JS declaration starting at `start`.
   Scans forward tracking string / template / comment / regex state and
   bracket depth; the form ends at the `;` or newline where depth is back to
   zero. Throws at EOF — a mis-scan fails the build rather than baking a
   truncated form."
  [^String src ^long start]
  (let [n (String/.length src)]
    (loop [i start, state :code, depth 0, opened? false]
      (when (>= i n)
        (throw (ex-info "JS anchor: unbalanced form (hit EOF)" {:start start})))
      (let [c (String/.charAt src i)
            nxt (when (< (inc i) n) (String/.charAt src (inc i)))]
        (case state
          :line-comment (recur (inc i) (if (= c \newline) :code state) depth opened?)
          :block-comment (if (and (= c \*) (= nxt \/))
                           (recur (+ i 2) :code depth opened?)
                           (recur (inc i) state depth opened?))
          (:sq :dq :tpl :regex)
          (cond
            (= c \\) (recur (+ i 2) state depth opened?)
            (or (and (= state :sq) (= c \'))
                (and (= state :dq) (= c \"))
                (and (= state :tpl) (= c \`))
                (and (= state :regex) (= c \/)))
            (recur (inc i) :code depth opened?)
            :else (recur (inc i) state depth opened?))
          :code
          (cond
            (and (= c \/) (= nxt \/)) (recur (+ i 2) :line-comment depth opened?)
            (and (= c \/) (= nxt \*)) (recur (+ i 2) :block-comment depth opened?)
            (and (= c \/) (regex-position? src i)) (recur (inc i) :regex depth opened?)
            (= c \') (recur (inc i) :sq depth opened?)
            (= c \") (recur (inc i) :dq depth opened?)
            (= c \`) (recur (inc i) :tpl depth opened?)
            (contains? #{\( \[ \{} c) (recur (inc i) state (inc depth) true)
            (contains? #{\) \] \}} c) (recur (inc i) state (dec depth) opened?)
            (and (zero? depth) (= c \;)) (inc i)
            (and (zero? depth) opened? (= c \newline)) i
            :else (recur (inc i) state depth opened?)))))))


(defn- find-js-decl
  "Locate the declaration of `sym` in a JS source. Returns {:code :line} or
   throws on 0 / >1 matches — the same uniqueness contract the Clojure
   anchors carry."
  [^String src sym file]
  (let [re (js-decl-re sym)
        lines (str/split-lines src)
        ;; offset of each line's first char
        offsets (reductions + 0 (map #(inc (count %)) lines))
        hits (for [[idx line off] (map vector (range) lines offsets)
                   :when (re-find re line)]
               {:line (inc idx)
                :code (subs src off (js-form-end src off))})]
    (case (count hits)
      1 (first hits)
      0 (throw (ex-info (str "anchor not found: " sym " in " file) {:sym sym :file file}))
      (throw (ex-info (str "anchor ambiguous: " sym " appears " (count hits)
                           "x in " file " — rename or split the form")
                      {:sym sym :file file :count (count hits)})))))


(defn- resolve-anchor
  "Resolve one step's anchor to baked source, or throw. The anchor is
   {:defn sym} plus EITHER :ns (a src/ namespace, munged to a path) OR :file
   (an explicit repo-relative path — used for package impls under
   resources/packages/, which have namespaces but do not live under src/,
   and for the editor's `.js` modules).
   For a defmethod, set :defn to the method symbol and :dispatch to its
   dispatch value; the step is then labelled by the dispatch's name.
   A `.js` :file resolves through the JS declaration scanner instead of
   rewrite-clj; :dispatch is meaningless there."
  [{ns-sym :ns sym :defn file :file dispatch :dispatch}]
  (when-not sym
    (throw (ex-info "step needs :defn" {:ns ns-sym :file file})))
  (let [path (or file (some-> ns-sym ns->path))
        label (cond
                (nil? dispatch) (str sym)
                (keyword? dispatch) (name dispatch)
                :else (str dispatch))]
    (when-not path
      (throw (ex-info "step needs :ns or :file" {:defn sym})))
    (when-not (fs/exists? path)
      (throw (ex-info (str "anchor file missing: " path) {:defn sym})))
    (let [js? (str/ends-with? path ".js")]
      (when (and js? dispatch)
        (throw (ex-info (str ":dispatch is Clojure-only, not valid for " path)
                        {:defn sym})))
      (-> (if js?
            (find-js-decl (slurp path) sym path)
            (find-form (slurp path) sym dispatch path))
          (assoc :file path :ns (str (or ns-sym path)) :defn label
                 :lang (if js? "js" "clj"))))))


;; --- model -----------------------------------------------------------------

(defn- resolve-step
  "Anchor + prose for one step. `:gi` (global spine index) and `:n` (1-based
   position within the block) are assigned by the caller; `:see` is resolved
   in a second pass once every step's `:gi` is known."
  [block-id step]
  (-> (resolve-anchor step)
      (assoc :say (:say step) :block (str block-id))
      (cond-> (:see step) (assoc :raw-see (:see step)))))


(defn- build-model
  "Resolve every toured block's anchors; validate stubs + :after edges; assign
   each toured step a stable global index and resolve its see-also links to
   those indices. Steps are identified by index, NOT by (block, defn) — a block
   may legitimately tour two forms of the same name (e.g. the executor's two
   `execute`s). Throws with block/step context on any bad anchor, and on a
   see-also target that is missing or ambiguous."
  [tour]
  (let [blocks (:blocks tour)
        ids (set (map :id blocks))]
    (doseq [b blocks, a (:after b)]
      (when-not (ids a)
        (throw (ex-info (str "block " (:id b) " :after unknown block " a) {}))))
    (let [gi (atom -1)
          base (vec (for [b blocks]
                      (cond-> {:id (str (:id b))
                               :title (:title b)
                               :status (name (:status b :stub))
                               :summary (:summary b)
                               :paths (:paths b)
                               :after (mapv str (:after b))}
                        (= :toured (:status b))
                        (assoc :steps
                               (vec (for [[i step] (map-indexed vector (:steps b))]
                                      (try (assoc (resolve-step (:id b) step)
                                                  :gi (swap! gi inc) :n (inc i))
                                           (catch Exception e
                                             (throw (ex-info
                                                      (str "block " (:id b) " step " i ": "
                                                           (ex-message e))
                                                      (ex-data e) e))))))))))
          spine (mapcat #(or (:steps %) []) base)
          by-key (reduce (fn [m s] (update m [(:block s) (:defn s)] (fnil conj []) (:gi s)))
                         {} spine)
          resolve-see
          (fn [owner raw]
            (mapv (fn [pair]
                    (let [k (mapv str pair)
                          hits (get by-key k)]
                      (when-not hits
                        (throw (ex-info (str "see-also target not found: " k
                                             " (from " owner ")") {})))
                      (when (> (count hits) 1)
                        (throw (ex-info (str "see-also ambiguous: " k " (from " owner
                                             ") — target appears " (count hits) "x") {})))
                      {:gi (first hits) :label (second k)}))
                  raw))
          finalize (fn [s]
                     (-> s
                         (cond-> (:raw-see s)
                           (assoc :see (resolve-see (:defn s) (:raw-see s))))
                         (dissoc :raw-see :block)))]
      {:title (:title tour)
       :intro (:intro tour)
       :blocks (mapv (fn [b] (cond-> b (:steps b) (update :steps #(mapv finalize %))))
                     base)})))


;; --- HTML render -----------------------------------------------------------

(declare css js)


(defn- page
  ^String [model]
  (let [data (-> (json/generate-string model)
                 ;; keep the JSON safe inside a <script> element
                 (str/replace "</" "<\\/"))]
    (str "<!doctype html>
<html lang=\"en\" data-theme=\"dark\">
<head>
<meta charset=\"utf-8\">
<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
<title>" (:title model) "</title>
<style>" css "</style>
</head>
<body>
<header id=\"top\"><h1>" (:title model) "</h1>
<div id=\"crumb\"></div></header>
<div id=\"shell\">
  <nav id=\"map\" aria-label=\"tour map\"></nav>
  <main id=\"stage\"></main>
</div>
<footer id=\"nav\">
  <button id=\"back\" title=\"where you came from\">← Back</button>
  <button id=\"prev\">‹ Prev</button>
  <span id=\"pos\"></span>
  <button id=\"next\">Next ›</button>
</footer>
<script id=\"tour-data\" type=\"application/json\">" data "</script>
<script>" js "</script>
</body>
</html>
")))


(def ^:private css
  "
:root{--bg:#0e1116;--panel:#161b22;--edge:#2a323d;--fg:#e6edf3;--dim:#8b949e;
--accent:#58a6ff;--hi:#213048;--kw:#79c0ff;--str:#a5d6a2;--cmt:#6e7681;--stub:#484f58}
*{box-sizing:border-box}
html,body{margin:0;height:100%}
body{background:var(--bg);color:var(--fg);
font:14px/1.55 -apple-system,Segoe UI,Roboto,sans-serif;display:flex;flex-direction:column}
header{padding:10px 18px;border-bottom:1px solid var(--edge);display:flex;
align-items:baseline;gap:16px;flex:0 0 auto}
header h1{font-size:15px;margin:0;font-weight:600}
#crumb{color:var(--dim);font-size:12px}
#crumb b{color:var(--fg);font-weight:600}
#shell{flex:1 1 auto;display:flex;min-height:0}
#map{width:290px;flex:0 0 auto;overflow:auto;border-right:1px solid var(--edge);
padding:12px 8px;background:var(--panel)}
#stage{flex:1 1 auto;overflow:auto;padding:22px 28px;max-width:980px}
footer{flex:0 0 auto;display:flex;gap:10px;align-items:center;
padding:9px 18px;border-top:1px solid var(--edge);background:var(--panel)}
footer button{background:#21262d;color:var(--fg);border:1px solid var(--edge);
border-radius:6px;padding:5px 12px;cursor:pointer;font-size:13px}
footer button:hover:not(:disabled){border-color:var(--accent)}
footer button:disabled{opacity:.4;cursor:default}
#pos{color:var(--dim);font-size:12px;margin:0 6px}
.blk{margin-bottom:6px}
.blk-h{padding:6px 8px;border-radius:6px;cursor:default}
.blk-h .t{font-weight:600}
.blk-h .s{color:var(--dim);font-size:11.5px;display:block;margin-top:2px}
.blk.stub .blk-h{opacity:.62}
.blk.stub .t::after{content:' ⏳';font-size:11px}
.blk .after{color:var(--stub);font-size:10.5px;margin:1px 0 0 8px}
.steps{list-style:none;margin:4px 0 0;padding:0 0 0 8px}
.steps li{padding:3px 8px;border-radius:5px;cursor:pointer;color:var(--dim);
font-size:12.5px;border-left:2px solid transparent}
.steps li:hover{color:var(--fg);background:#1c2330}
.steps li.on{color:var(--fg);background:var(--hi);border-left-color:var(--accent)}
.paths{margin:6px 0 0 8px}
.paths code{color:var(--dim);font-size:11px;display:block}
.say{margin:0 0 16px}
.say p{margin:0 0 10px}
.say code,code.inl{background:#1c2330;border-radius:4px;padding:.5px 5px;
font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12.5px}
.say a{color:var(--accent)}
.file{color:var(--dim);font-size:12px;margin:0 0 6px;
font-family:ui-monospace,monospace}
.file b{color:var(--fg)}
pre.code{background:var(--panel);border:1px solid var(--edge);border-radius:8px;
padding:12px 0;overflow:auto;margin:0 0 18px;font-size:12.5px;line-height:1.5}
pre.code .ln{display:flex}
pre.code .g{color:var(--cmt);text-align:right;padding:0 14px 0 12px;
user-select:none;min-width:52px;flex:0 0 auto}
pre.code .c{padding-right:16px;white-space:pre;
font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
pre.code .head .c{background:var(--hi)}
.tok-kw{color:var(--kw)}.tok-str{color:var(--str)}.tok-cmt{color:var(--cmt)}
.see{margin:0 0 18px}
.see .lbl{color:var(--dim);font-size:11.5px;margin-right:6px}
.see a{display:inline-block;background:#1c2330;border:1px solid var(--edge);
border-radius:12px;padding:2px 10px;font-size:12px;color:var(--accent);
cursor:pointer;margin:0 6px 6px 0}
.stub-note{color:var(--dim);border:1px dashed var(--edge);border-radius:8px;
padding:16px;margin-top:12px}
.intro{color:var(--dim);max-width:760px}
kbd{background:#21262d;border:1px solid var(--edge);border-bottom-width:2px;
border-radius:4px;padding:0 5px;font-size:11px}
")


(def ^:private js
  "
const MODEL = JSON.parse(document.getElementById('tour-data').textContent);
// flatten toured steps into a single ordered spine — index === step.gi
const SPINE = [];
MODEL.blocks.forEach(b => (b.steps||[]).forEach(s => SPINE.push({block:b, step:s})));

let cur = 0;            // current global step index into SPINE (or -1 = intro)
const hist = [];        // back-stack of previously-viewed indices

const esc = s => s.replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));

// tiny markdown: paragraphs, `code`, **bold**, [text](url)
function md(src){
  return (src||'').split(/\\n\\s*\\n/).map(p => {
    let h = esc(p);
    h = h.replace(/`([^`]+)`/g, (_,x)=>'<code class=\"inl\">'+x+'</code>');
    h = h.replace(/\\*\\*([^*]+)\\*\\*/g, (_,x)=>'<b>'+x+'</b>');
    h = h.replace(/\\[([^\\]]+)\\]\\(([^)]+)\\)/g,
      (_,t,u)=>'<a href=\"'+esc(u)+'\">'+esc(t)+'</a>');
    return '<p>'+h+'</p>';
  }).join('');
}

// minimal JS tokeniser — strings, comments, keywords. Line-scoped like the
// Clojure one: good enough to read, never claims to parse.
const JS_KW = new RegExp('\\\\b(async|await|break|case|catch|class|const|continue|'+
  'default|delete|do|else|export|extends|finally|for|function|if|import|in|'+
  'instanceof|let|new|of|return|super|switch|this|throw|try|typeof|var|void|'+
  'while|yield|null|undefined|true|false)\\\\b','g');

function hlJs(line){
  let out='', i=0;
  while(i<line.length){
    const c=line[i];
    if(c==='/'&&line[i+1]==='/'){ out+='<span class=tok-cmt>'+esc(line.slice(i))+'</span>'; break; }
    if(c==='*'&&/^\\s*\\*/.test(line)&&i===line.search(/\\S/)){
      out+='<span class=tok-cmt>'+esc(line.slice(i))+'</span>'; break; }
    if(c==='\"'||c===\"'\"||c==='`'){ let j=i+1;
      while(j<line.length){ if(line[j]==='\\\\'){j+=2;continue;} if(line[j]===c){j++;break;} j++; }
      out+='<span class=tok-str>'+esc(line.slice(i,j))+'</span>'; i=j; continue; }
    let j=i+1;
    while(j<line.length && line[j]!=='\"' && line[j]!==\"'\" && line[j]!=='`'
          && !(line[j]==='/'&&line[j+1]==='/')) j++;
    out+=esc(line.slice(i,j)).replace(JS_KW,m=>'<span class=tok-kw>'+m+'</span>');
    i=j;
  }
  return out;
}

// minimal Clojure tokeniser -> highlighted HTML for one line
function hl(line){
  let out='', i=0;
  while(i<line.length){
    const c=line[i];
    if(c===';'){ out+='<span class=tok-cmt>'+esc(line.slice(i))+'</span>'; break; }
    if(c==='\"'){ let j=i+1; while(j<line.length){ if(line[j]==='\\\\'){j+=2;continue;}
      if(line[j]==='\"'){j++;break;} j++; }
      out+='<span class=tok-str>'+esc(line.slice(i,j))+'</span>'; i=j; continue; }
    if(c===':'){ let j=i+1; while(j<line.length && /[\\w*+!?<>=./-]/.test(line[j])) j++;
      out+='<span class=tok-kw>'+esc(line.slice(i,j))+'</span>'; i=j; continue; }
    let j=i+1; while(j<line.length && line[j]!==';' && line[j]!=='\"' && line[j]!==':') j++;
    out+=esc(line.slice(i,j)); i=j;
  }
  return out;
}

function codeBlock(step){
  const lines = step.code.split('\\n');
  const paint = step.lang==='js' ? hlJs : hl;
  const rows = lines.map((ln,k)=>{
    const n = step.line + k;
    const head = k===0 ? ' head' : '';
    return '<div class=\"ln'+head+'\"><span class=g>'+n+
      '</span><span class=c>'+paint(ln)+'</span></div>';
  }).join('');
  return '<div class=file>'+esc(step.file)+' — <b>'+esc(step.defn)+
    '</b></div><pre class=code>'+rows+'</pre>';
}

function seeBlock(step){
  if(!step.see||!step.see.length) return '';
  const chips = step.see.map(x=>
    '<a data-gi=\"'+x.gi+'\">'+esc(x.label)+'</a>').join('');
  return '<div class=see><span class=lbl>see also</span>'+chips+'</div>';
}

function renderIntro(){
  cur=-1;
  document.getElementById('crumb').innerHTML='';
  document.getElementById('stage').innerHTML =
    '<div class=intro>'+md(MODEL.intro||'')+
    '<p style=\"margin-top:18px\">Pick a step on the left, or press '+
    '<kbd>→</kbd> to start. <kbd>←</kbd>/<kbd>→</kbd> walk the spine; '+
    '<b>Back</b> returns along the path you actually took.</p></div>';
  paint();
}

function renderStub(b){
  document.getElementById('crumb').innerHTML='<b>'+esc(b.title)+'</b>';
  document.getElementById('stage').innerHTML =
    '<h2>'+esc(b.title)+'</h2><div class=say>'+md(b.summary||'')+'</div>'+
    '<div class=stub-note>Not toured yet — this block is a stub. '+
    'Code lives under:<div class=paths>'+
    (b.paths||[]).map(p=>'<code>'+esc(p)+'</code>').join('')+
    '</div><p style=\"margin:10px 0 0\">Add steps to this block in '+
    '<code class=inl>docs/devtour/tour.edn</code>, then run '+
    '<code class=inl>bb devtour</code>.</p></div>';
  paint();
}

function go(gi, push){
  if(push && cur>=0) hist.push(cur);
  cur = gi;
  const {block, step} = SPINE[gi];
  document.getElementById('crumb').innerHTML =
    '<b>'+esc(block.title)+'</b> › step '+step.n+' / '+(block.steps||[]).length+
    ' — <code class=inl>'+esc(step.defn)+'</code>';
  document.getElementById('stage').innerHTML =
    '<div class=say>'+md(step.say)+'</div>'+seeBlock(step)+codeBlock(step);
  document.getElementById('stage').scrollTop=0;
  paint();
}

function paint(){
  document.querySelectorAll('.steps li').forEach(li=>{
    const on = +li.dataset.gi===cur;
    li.classList.toggle('on', on);
    // The spine is long enough that walking it with Next scrolls the current
    // step out of the map entirely — keep the highlight in view.
    if(on) li.scrollIntoView({block:'nearest'});
  });
  document.getElementById('pos').textContent =
    cur<0 ? '' : ('step '+(cur+1)+' / '+SPINE.length);
  document.getElementById('prev').disabled = cur<=0;
  document.getElementById('next').disabled = cur>=SPINE.length-1;
  document.getElementById('back').disabled = hist.length===0;
}

function buildMap(){
  const map=document.getElementById('map');
  MODEL.blocks.forEach(b=>{
    const wrap=document.createElement('div');
    wrap.className='blk '+(b.status==='toured'?'toured':'stub');
    let html='<div class=blk-h><span class=t>'+esc(b.title)+'</span>'+
      '<span class=s>'+esc(b.summary||'')+'</span></div>';
    if(b.after&&b.after.length)
      html+='<div class=after>after: '+b.after.map(esc).join(', ')+'</div>';
    if(b.status==='toured'){
      html+='<ul class=steps>'+ (b.steps||[]).map(s=>
        '<li data-gi=\"'+s.gi+'\">'+esc(s.defn)+'</li>').join('') +'</ul>';
    }
    wrap.innerHTML=html;
    if(b.status!=='toured')
      wrap.querySelector('.blk-h').onclick=()=>renderStub(b);
    wrap.querySelectorAll('.steps li').forEach(li=>
      li.onclick=()=>go(+li.dataset.gi,true));
    map.appendChild(wrap);
  });
}

document.getElementById('next').onclick=()=>{ if(cur<SPINE.length-1) go(cur+1,true); };
document.getElementById('prev').onclick=()=>{ if(cur>0) go(cur-1,true); };
document.getElementById('back').onclick=()=>{ if(hist.length) go(hist.pop(),false); };
document.addEventListener('keydown',e=>{
  if(e.target.tagName==='INPUT') return;
  if(e.key==='ArrowRight' && cur<SPINE.length-1) go(cur<0?0:cur+1,true);
  if(e.key==='ArrowLeft' && cur>0) go(cur-1,true);
});
document.getElementById('stage').addEventListener('click',e=>{
  const a=e.target.closest('a[data-gi]');
  if(a){ e.preventDefault(); go(+a.dataset.gi,true); }
});

buildMap();
renderIntro();
")


;; --- entry -----------------------------------------------------------------

(defn- build!
  []
  (let [tour (-> tour-edn slurp read-string)
        model (build-model tour)]
    (spit out-html (page model))
    (println "devtour: wrote" out-html
             (str "(" (count (mapcat :steps (:blocks model))) " steps, "
                  (count (:blocks model)) " blocks)"))))


(defn- check!
  []
  (let [tour (-> tour-edn slurp read-string)
        model (build-model tour)          ; throws on any broken anchor
        fresh (page model)
        current (when (fs/exists? out-html) (slurp out-html))]
    (when-not (= fresh current)
      (binding [*out* *err*]
        (println "devtour-check FAILED:" out-html
                 "is stale (source drifted from the baked tour).")
        (println "  Run `bb devtour` and commit the regenerated HTML."))
      (System/exit 1))
    (println "devtour-check OK:"
             (count (mapcat :steps (:blocks model))) "anchors resolve;"
             out-html "is up to date")))


(let [cmd (first *command-line-args*)]
  (try
    (case cmd
      "check" (check!)
      (build!))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println "devtour FAILED:" (ex-message e)))
      (System/exit 1))))
