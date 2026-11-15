/* DocumentFacilitator in-page editor. Runs inside the document WebView (and in tests in a plain browser). */
(function () {
  'use strict';
  var root = document.getElementById('df');
  var mode = 0;               // 0 view, 1 edit, 2 fill
  var structural = false;     // an edit changed more than run text
  var orig = {};              // data-r id -> original text
  var counts = {};
  var isGrid = document.body.classList.contains('grid');
  var host = window.DF || { dirty: function () {}, ready: function () {} };
  var act = null;             // active grid cell

  function dirty() { try { host.dirty(); } catch (e) {} }
  function all(sel, el) { return Array.prototype.slice.call((el || root).querySelectorAll(sel)); }

  function recordOriginal() {
    orig = {};
    all('[data-r]').forEach(function (s) { orig[s.getAttribute('data-r')] = s.textContent; });
    counts = { img: all('img').length, table: all('table').length, tr: all('tr').length, td: all('td').length, runs: all('[data-r]').length };
  }

  /* ---------- sheets ---------- */
  function showSheet(i) {
    all('.sheet').forEach(function (s, k) { s.classList.toggle('on', k === i); });
    var tabs = document.getElementById('dftabs');
    if (tabs) Array.prototype.forEach.call(tabs.children, function (t, k) { t.classList.toggle('on', k === i); });
  }
  function buildTabs() {
    var sheets = all('.sheet');
    if (!sheets.length) return;
    var old = document.getElementById('dftabs');
    if (old) old.remove();
    if (sheets.length > 1) {
      var bar = document.createElement('div');
      bar.id = 'dftabs';
      sheets.forEach(function (s, i) {
        var t = document.createElement('span');
        t.textContent = s.getAttribute('data-n') || ('Sheet ' + (i + 1));
        t.addEventListener('click', function () { deactivate(); showSheet(i); });
        bar.appendChild(t);
      });
      document.body.appendChild(bar);
    }
    var on = sheets.findIndex(function (s) { return s.classList.contains('on'); });
    showSheet(on < 0 ? 0 : on);
  }
  function colName(c) { var s = ''; c++; while (c > 0) { var m = (c - 1) % 26; s = String.fromCharCode(65 + m) + s; c = (c - 1 - m) / 26; } return s; }
  function cellRef(td) { return colName(td.cellIndex - 1) + td.parentNode.rowIndex; }

  function deactivate() {
    if (!act) return;
    var td = act; act = null;
    td.contentEditable = 'false';
    td.classList.remove('act');
    var o = td.getAttribute('data-o');
    var f = td.getAttribute('data-f');
    var t = td.textContent;
    if (f !== null) {
      if (t === f) { td.textContent = td.getAttribute('data-v') || ''; }   // formula untouched: show its value again
      else { td.removeAttribute('data-f'); td.classList.add('chg'); dirty(); }
      td.removeAttribute('data-v');
    } else if (o !== null && t !== o) { td.classList.add('chg'); dirty(); }
    else if (o !== null) td.classList.remove('chg');
  }
  function activate(td) {
    if (act === td) return;
    deactivate();
    if (mode === 0 || !td || td.tagName !== 'TD') return;
    act = td;
    if (!td.hasAttribute('data-o')) td.setAttribute('data-o', td.hasAttribute('data-f') ? td.getAttribute('data-f') : td.textContent);
    if (td.hasAttribute('data-f')) { td.setAttribute('data-v', td.textContent); td.textContent = td.getAttribute('data-f'); }
    td.contentEditable = 'true';
    td.classList.add('act');
    td.focus();
    var r = document.createRange(); r.selectNodeContents(td);
    var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
  }
  function neighbour(td, dr, dc) {
    var row = td.parentNode.parentNode.rows[td.parentNode.rowIndex + dr];
    return row ? row.cells[td.cellIndex + dc] : null;
  }

  /* ---------- fill mode: find the blanks ---------- */
  var BLANK = /_{3,}|\.{6,}|…{3,}|[☐☑☒]|\[ ?[xX]? ?\](?!\()|\[\s{2,}\]|\(\s{3,}\)|\[[A-Z][A-Za-z0-9 ,.'\/\-]{1,40}\]|\{\{[^{}]{1,40}\}\}|<<[^<>]{1,40}>>|Click (?:or tap )?here to enter (?:text|a date)\.?/g;
  function isCheck(s) { return /^[☐☑☒]$/.test(s) || /^\[ ?[xX]? ?\]$/.test(s); }

  function clearFields() {
    all('.dfw').forEach(function (w) {            // wrappers we created around text
      var p = w.parentNode;
      while (w.firstChild) p.insertBefore(w.firstChild, w);
      p.removeChild(w); p.normalize();
    });
    all('.dff,.dfc').forEach(function (e) {
      e.classList.remove('dff', 'dfc', 'done');
      e.removeAttribute('contenteditable'); e.removeAttribute('data-ph'); e.removeAttribute('tabindex');
      if (e.getAttribute('class') === '') e.removeAttribute('class');
    });
  }

  function markField(el, ph) {
    el.classList.add('dff');
    el.contentEditable = 'true';
    if (ph != null) el.setAttribute('data-ph', ph);
  }

  function findFields() {
    clearFields();
    if (isGrid) {
      all('.sheet.on td').forEach(function (td) {
        var prev = td.previousElementSibling;
        if (td.textContent === '' && prev && prev.tagName === 'TD' && /[:?]\s*$/.test(prev.textContent)) td.classList.add('dff');
        else if (/^_{3,}$/.test(td.textContent.trim())) td.classList.add('dff');
      });
      return all('.dff').length;
    }
    // 1. structured fields the document itself declares
    all('.sdt,.ff').forEach(function (e) { if (!e.closest('.dff')) markField(e, e.classList.contains('ph') ? e.textContent : null); });
    // 2. textual blanks: underscore runs, dot leaders, bracket placeholders, check boxes
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var nodes = [], n;
    while ((n = walker.nextNode())) {
      var pe = n.parentNode;
      if (pe.closest('.dff,.x,script,style,pre,code')) continue;
      BLANK.lastIndex = 0;
      if (BLANK.test(n.nodeValue)) nodes.push(n);
    }
    nodes.forEach(function (node) {
      var text = node.nodeValue, last = 0, m, frag = document.createDocumentFragment();
      BLANK.lastIndex = 0;
      while ((m = BLANK.exec(text))) {
        if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)));
        var w = document.createElement('span');
        w.textContent = m[0];
        if (isCheck(m[0])) { w.className = 'dfw dfc'; if (!/^(☐|\[ ?\])$/.test(m[0])) w.classList.add('done'); }
        else { w.className = 'dfw dff'; w.contentEditable = 'true'; w.setAttribute('data-ph', m[0]); }
        frag.appendChild(w);
        last = m.index + m[0].length;
      }
      if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
      node.parentNode.replaceChild(frag, node);
    });
    // 3. empty table cells that sit beside or below a caption
    all('td').forEach(function (td) {
      if (td.textContent.trim() !== '' || td.querySelector('img,table,.dff')) return;
      var row = td.parentNode, hasLabel = false;
      Array.prototype.forEach.call(row.cells, function (c) { if (c !== td && c.textContent.trim() !== '') hasLabel = true; });
      if (!hasLabel) {
        var above = row.previousElementSibling;
        var up = above && above.cells[td.cellIndex];
        hasLabel = !!(up && up.textContent.trim() !== '' && !up.querySelector('.dff'));
      }
      if (!hasLabel) return;
      var target = td.querySelector('[data-e]') || td.querySelector('p') || td;
      markField(target, null);
    });
    // 4. "Label:" paragraph followed by an empty paragraph
    all('p,h1,h2,h3,h4,h5,h6,.tp').forEach(function (p) {
      if (!/[:?]\s*$/.test(p.textContent)) return;
      var nx = p.nextElementSibling;
      if (nx && nx.hasAttribute('data-e') && nx.textContent.trim() === '' && !nx.classList.contains('dff')) markField(nx, null);
    });
    all('.dff').forEach(function (e) { e.setAttribute('tabindex', '0'); refreshDone(e); });
    return all('.dff,.dfc').length + all('input,textarea,select').length;
  }

  function refreshDone(e) {
    var ph = e.getAttribute('data-ph');
    var t = e.textContent;
    e.classList.toggle('done', t.trim() !== '' && t !== ph);
  }

  function fieldList() { return all('.dff,.dfc,input,textarea,select').filter(function (e) { return e.offsetParent !== null; }); }

  function stepField(dir) {
    var list = fieldList();
    if (!list.length) return 0;
    var cur = document.activeElement;
    var i = list.indexOf(cur && cur.closest ? (cur.closest('.dff,.dfc') || cur) : cur);
    if (i < 0 && window.__dfLast) i = list.indexOf(window.__dfLast);
    i = i < 0 ? (dir > 0 ? 0 : list.length - 1) : i + dir;
    if (i < 0 || i >= list.length) return -1;
    var e = list[i];
    window.__dfLast = e;
    e.scrollIntoView({ block: 'center' });
    if (isGrid) activate(e); else if (e.classList.contains('dfc')) { e.setAttribute('tabindex', '0'); e.focus(); } else e.focus();
    return list.length;
  }

  function toggleCheck(e) {
    var t = e.textContent, n;
    if (t === '☐') n = '☒';
    else if (t === '☑' || t === '☒') n = '☐';
    else n = /[xX]/.test(t) ? '[ ]' : '[x]';
    e.textContent = n;
    e.classList.toggle('done', n !== '☐' && n !== '[ ]');
    dirty();
  }

  /* ---------- events ---------- */
  root.addEventListener('input', function (ev) {
    var t = ev.target;
    if (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT') {   // keep form values in the markup so states capture them
      if (t.type === 'checkbox' || t.type === 'radio') all('input[type=' + t.type + ']').forEach(function (i) { if (i.checked) i.setAttribute('checked', ''); else i.removeAttribute('checked'); });
      else if (t.tagName === 'TEXTAREA') t.textContent = t.value;
      else if (t.tagName === 'SELECT') Array.prototype.forEach.call(t.options, function (o) { if (o.selected) o.setAttribute('selected', ''); else o.removeAttribute('selected'); });
      else t.setAttribute('value', t.value);
    }
    var f = t.closest ? t.closest('.dff') : null;
    if (f) refreshDone(f);
    dirty();
  });
  root.addEventListener('change', function () { dirty(); });
  root.addEventListener('beforeinput', function (ev) {
    var ty = ev.inputType || '';
    if (mode === 2 || isGrid) {
      if (ty === 'insertParagraph' || ty === 'insertLineBreak') { ev.preventDefault(); if (isGrid && act) { var d = neighbour(act, 1, 0); if (d) activate(d); } else stepField(1); }
      else if (ty.indexOf('format') === 0 || ty === 'insertFromDrop') ev.preventDefault();
      return;
    }
    if (!/^(insertText|insertCompositionText|insertReplacementText|deleteContentBackward|deleteContentForward|deleteWordBackward|deleteWordForward|deleteByCut|insertFromPaste|historyUndo|historyRedo)$/.test(ty)) structural = true;
  });
  root.addEventListener('paste', function (ev) {      // paste as plain text keeps runs intact
    if (mode === 0) return;
    ev.preventDefault();
    var t = (ev.clipboardData || window.clipboardData).getData('text/plain');
    if (mode === 2 || isGrid) t = t.replace(/[\r\n]+/g, ' ');
    document.execCommand('insertText', false, t);
  });
  root.addEventListener('focusin', function (ev) {
    var f = ev.target.closest ? ev.target.closest('.dff') : null;
    if (!f || isGrid) return;
    window.__dfLast = f;
    if (f.getAttribute('data-ph') === f.textContent && f.textContent !== '') {     // typing replaces the placeholder
      var r = document.createRange(); r.selectNodeContents(f);
      var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
    }
  });
  root.addEventListener('focusout', function (ev) {
    var f = ev.target.closest ? ev.target.closest('.dff') : null;
    if (!f || isGrid) return;
    var ph = f.getAttribute('data-ph');
    if (ph && f.textContent.trim() === '' && f.classList.contains('dfw')) f.textContent = ph;   // emptied: put the blank back
    refreshDone(f);
  });
  root.addEventListener('click', function (ev) {
    var t = ev.target;
    if (isGrid) { var td = t.closest ? t.closest('td') : null; if (td && mode !== 0) activate(td); else deactivate(); return; }
    if (mode === 2 && t.closest) { var c = t.closest('.dfc'); if (c) { window.__dfLast = c; toggleCheck(c); } }
    if (t.closest && t.closest('a')) ev.preventDefault();
  });
  root.addEventListener('keydown', function (ev) {
    if (isGrid && act) {
      if (ev.key === 'Tab') { ev.preventDefault(); var n = neighbour(act, 0, ev.shiftKey ? -1 : 1); if (n && n.tagName === 'TD') activate(n); }
      else if (ev.key === 'Enter') { ev.preventDefault(); var d = neighbour(act, 1, 0); if (d) activate(d); }
      else if (ev.key === 'Escape') deactivate();
    } else if (mode === 2 && ev.key === 'Tab') { ev.preventDefault(); stepField(ev.shiftKey ? -1 : 1); }
    else if (mode === 2 && (ev.key === ' ' || ev.key === 'Enter') && ev.target.classList && ev.target.classList.contains('dfc')) { ev.preventDefault(); toggleCheck(ev.target); }
  });

  /* ---------- collecting results ---------- */
  function cleanClone() {
    var c = root.cloneNode(true);
    all('.dfw', c).forEach(function (w) { var p = w.parentNode; while (w.firstChild) p.insertBefore(w.firstChild, w); p.removeChild(w); });
    all('.dff,.dfc,.act', c).forEach(function (e) { e.classList.remove('dff', 'dfc', 'done', 'act'); e.removeAttribute('contenteditable'); e.removeAttribute('data-ph'); e.removeAttribute('tabindex'); if (e.getAttribute('class') === '') e.removeAttribute('class'); });
    c.normalize();
    return c;
  }

  /* Text-only changes of an OOXML document: {ok, runs:{id:text}, paras:{id:text}}. ok=false when structure changed. */
  function collectPatch() {
    var c = cleanClone();
    var res = { ok: true, runs: {}, paras: {}, why: '' };
    var seen = {};
    var runs = all('[data-r]', c);
    if (structural) { res.ok = false; res.why = 'formatting or structure was edited'; }
    if (runs.length !== counts.runs) { res.ok = false; res.why = 'text runs were added or removed'; }
    ['img', 'table', 'tr', 'td'].forEach(function (k) { if (all(k, c).length !== counts[k]) { res.ok = false; res.why = k + ' count changed'; } });
    runs.forEach(function (s) {
      var id = s.getAttribute('data-r');
      if (seen[id]) { res.ok = false; res.why = 'a paragraph was split'; }
      seen[id] = 1;
      if (s.children.length) { res.ok = false; res.why = 'formatting inside a run'; }
      if (s.textContent !== orig[id]) res.runs[id] = s.textContent.replace(/ /g, ' ');
    });
    all('[data-e]', c).forEach(function (p) {
      if (p.querySelector('[data-r],[data-e]')) return;
      var t = p.textContent.replace(/ /g, ' ');
      if (t.trim() !== '') { var id = p.getAttribute('data-e'); if (seen['e' + id]) { res.ok = false; res.why = 'a paragraph was split'; } seen['e' + id] = 1; res.paras[id] = t; }
    });
    // anything typed outside the addressable runs cannot be patched
    all('[data-r],[data-e],.x', c).forEach(function (e) { if (e.parentNode) e.parentNode.removeChild(e); });
    if (c.textContent.replace(/[\s ]/g, '') !== '') { res.ok = false; res.why = 'text was added outside existing paragraphs'; }
    return res;
  }

  function rgbHex(v) {
    var m = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?/.exec(v || '');
    if (!m || (m[4] !== undefined && parseFloat(m[4]) === 0)) return '';
    return [1, 2, 3].map(function (i) { return ('0' + parseInt(m[i], 10).toString(16)).slice(-2); }).join('');
  }

  /* Block model for rebuilding a document (DOCX) from whatever the editor now contains. */
  function collectBlocks() {
    var c = root;      // computed styles need the live tree
    var out = [];
    var listId = 0;
    var baseSize = parseFloat(getComputedStyle(root).fontSize) || 16;
    function runsOf(el, runs) {
      Array.prototype.forEach.call(el.childNodes, function (n) {
        if (n.nodeType === 3) {
          var t = n.nodeValue.replace(/[\r\n\t]+/g, ' ');
          if (t === '') return;
          var cs = getComputedStyle(n.parentNode);
          var r = { s: t };
          if (parseInt(cs.fontWeight, 10) >= 600 || cs.fontWeight === 'bold') r.b = true;
          if (cs.fontStyle === 'italic') r.i = true;
          var deco = cs.textDecorationLine || cs.textDecoration || '';
          var p = n.parentNode;
          while (p && p !== el.parentNode && deco.indexOf('underline') < 0 && deco.indexOf('line-through') < 0) { deco = getComputedStyle(p).textDecorationLine || ''; p = p.parentNode; if (p === root) break; }
          if (deco.indexOf('underline') >= 0) r.u = true;
          if (deco.indexOf('line-through') >= 0) r.k = true;
          var col = rgbHex(cs.color); if (col && col !== '111111' && col !== '000000') r.c = col;
          var px = parseFloat(cs.fontSize);
          if (px && Math.abs(px - baseSize) > 0.6 && !/^H\d$/.test(el.tagName)) r.z = Math.round(px * 0.75 * 2) / 2;
          var bg = rgbHex(cs.backgroundColor); if (bg && bg !== 'ffffff' && !n.parentNode.closest('.dff,.dfc')) r.h = bg;
          runs.push(r);
        } else if (n.nodeType === 1) {
          if (n.tagName === 'BR') { if (runs.length) runs[runs.length - 1].s += '\n'; else runs.push({ s: '\n' }); }
          else if (n.tagName === 'IMG') runs.push({ img: n.getAttribute('src'), w: Math.round(n.width || n.naturalWidth || 100), h: Math.round(n.height || n.naturalHeight || 100) });
          else if (n.classList.contains('x') && !n.classList.contains('pb')) { if (!n.classList.contains('sn') && !n.classList.contains('note')) runs.push({ s: n.textContent === ' ' ? '\t' : n.textContent }); }
          else if (getComputedStyle(n).display !== 'none') runsOf(n, runs);
        }
      });
    }
    function trimTail(runs) { var l = runs[runs.length - 1]; if (l && l.s && /\n$/.test(l.s)) { l.s = l.s.replace(/\n$/, ''); if (l.s === '') runs.pop(); } return runs; }
    function isBlock(n) { return n.nodeType === 1 && /^(P|DIV|H[1-6]|UL|OL|LI|TABLE|SECTION|BLOCKQUOTE|PRE|HR|ARTICLE|HEADER|FOOTER|MAIN|FIGURE|DL|DT|DD|TBODY)$/.test(n.tagName); }
    function para(el, extra, into) {
      var b = { t: 'p', runs: trimTail(runsOf2(el)) };
      var al = getComputedStyle(el).textAlign;
      if (al === 'center' || al === 'right' || al === 'justify') b.align = al;
      for (var k in extra) b[k] = extra[k];
      into.push(b);
    }
    function runsOf2(el) { var r = []; runsOf(el, r); return r; }
    function walk(el, into, ctx) {
      var inline = [];
      function flush() {
        if (!inline.length) return;
        var holder = document.createElement('span');
        var runs = [];
        inline.forEach(function (n) { if (n.nodeType === 3) { var w = { childNodes: [n] }; runsOf(w, runs); } else { var w2 = { childNodes: [n] }; runsOf(w2, runs); } });
        inline = [];
        if (runs.some(function (r) { return r.img || (r.s && r.s.trim() !== ''); })) { var b = { t: 'p', runs: trimTail(runs) }; for (var k in ctx) b[k] = ctx[k]; into.push(b); }
      }
      Array.prototype.forEach.call(el.childNodes, function (n) {
        if (n.nodeType === 3) { inline.push(n); return; }
        if (n.nodeType !== 1) return;
        if (n.id === 'dftabs' || n.classList.contains('sn') || n.classList.contains('note')) return;
        var tag = n.tagName;
        if (!isBlock(n)) { if (n.classList.contains('pb')) { flush(); into.push({ t: 'pb' }); } else inline.push(n); return; }
        flush();
        if (/^H[1-6]$/.test(tag)) para(n, { t: tag.toLowerCase() }, into);
        else if (tag === 'P' || tag === 'PRE' || tag === 'DT' || tag === 'DD') {
          if (n.classList.contains('li')) { var ol = n.classList.contains('ol'); var prev = n.previousElementSibling; if (!(prev && prev.classList.contains('li') && prev.classList.contains('ol') === ol)) listId++; para(n, { t: 'li', ol: ol, list: listId, lvl: Math.max(0, Math.round((parseFloat(n.style.marginLeft) || 1.5) / 1.5) - 1) }, into); }
          else if (Array.prototype.some.call(n.children, isBlock)) walk(n, into, ctx);
          else para(n, ctx.t ? { t: ctx.t, ol: ctx.ol, list: ctx.list, lvl: ctx.lvl } : {}, into);
        }
        else if (tag === 'UL' || tag === 'OL') { listId++; var id = listId; Array.prototype.forEach.call(n.children, function (li) { if (li.tagName === 'LI') walk(li, into, { t: 'li', ol: tag === 'OL', list: id, lvl: (ctx.t === 'li' ? (ctx.lvl || 0) + 1 : 0) }); }); }
        else if (tag === 'TABLE') {
          var rows = [];
          Array.prototype.forEach.call(n.rows, function (tr) { var cells = []; Array.prototype.forEach.call(tr.cells, function (td) { var cb = []; walk(td, cb, {}); cells.push(cb); }); rows.push(cells); });
          into.push({ t: 'table', rows: rows });
        }
        else if (tag === 'HR') into.push({ t: n.classList.contains('pb') ? 'pb' : 'p', runs: [] });
        else walk(n, into, ctx);
      });
      flush();
    }
    walk(c, out, {});
    return out;
  }

  /* Spreadsheet content: full grid per sheet plus the cells that differ from the loaded file. */
  function collectGrid() {
    deactivate();
    var sheets = [];
    all('.sheet').forEach(function (sec) {
      var rows = [], edits = {};
      var table = sec.querySelector('table');
      if (table) Array.prototype.forEach.call(table.rows, function (tr, ri) {
        if (ri === 0) return;
        var row = [];
        for (var ci = 1; ci < tr.cells.length; ci++) {
          var td = tr.cells[ci];
          var f = td.getAttribute('data-f');
          var v = f !== null ? f : td.textContent;
          row.push(v);
          var o = td.getAttribute('data-o');
          if (o !== null && o !== v) edits[colName(ci - 1) + ri] = v;
        }
        rows.push(row);
      });
      sheets.push({ name: sec.getAttribute('data-n') || 'Sheet', rows: rows, edits: edits });
    });
    var on = all('.sheet').findIndex(function (s) { return s.classList.contains('on'); });
    return { sheets: sheets, active: on < 0 ? 0 : on };
  }

  window.dfApi = {
    setMode: function (m) {
      deactivate();
      if (mode === 2 && m !== 2) clearFields();
      mode = m;
      root.contentEditable = (m === 1 && !isGrid) ? 'true' : 'false';
      var n = 0;
      if (m === 2) n = findFields();
      return n;
    },
    exec: function (cmd, val) { structural = true; root.focus(); document.execCommand(cmd, false, val === undefined ? null : val); dirty(); },
    undo: function () { document.execCommand('undo'); }, redo: function () { document.execCommand('redo'); },
    step: stepField,
    rescan: function () { return mode === 2 ? findFields() : 0; },
    snapshot: function () { deactivate(); return JSON.stringify({ html: root.innerHTML, structural: structural }); },
    restore: function (json) {
      var o = JSON.parse(json);
      deactivate();
      root.innerHTML = o.html; structural = !!o.structural;
      buildTabs();
      if (mode === 2) findFields();
    },
    patch: function () { return JSON.stringify(collectPatch()); },
    blocks: function () { return JSON.stringify(collectBlocks()); },
    grid: function () { return JSON.stringify(collectGrid()); },
    html: function () {
      var c = cleanClone();
      all('[data-r],[data-e],[data-o],[data-v],[contenteditable]', c).forEach(function (e) { ['data-r', 'data-e', 'data-o', 'data-v', 'contenteditable'].forEach(function (a) { e.removeAttribute(a); }); });
      all('.x', c).forEach(function (e) { if (e.classList.contains('pb')) { var hr = document.createElement('hr'); e.parentNode.replaceChild(hr, e); } else if (e.classList.contains('sn') || e.classList.contains('note')) e.parentNode.removeChild(e); else e.removeAttribute('class'); });
      return c.innerHTML;
    },
    text: function () { return root.innerText; },
    stats: function () { var t = root.innerText || ''; var w = t.trim().split(/\s+/).filter(function (x) { return x; }).length; return JSON.stringify({ words: w, chars: t.length }); },
    night: function (on) { document.documentElement.classList.toggle('night', !!on); },
    isStructural: function () { return structural; }
  };

  recordOriginal();
  buildTabs();
  try { host.ready(root.getElementsByTagName('*').length); } catch (e) {}
})();
