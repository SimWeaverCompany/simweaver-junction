/* ============================================================
   SimWeaver Config Assistant - shared parts:
   - form widgets bound to plain state objects (Rift.ca.input, select, check, seg, ...)
   - the .properties writer (escaping as java.util.Properties / SimwProperties read it)
   - the "files to create" panel (copy, download)
   Nothing here talks to the server: the page only reads lists (optional).
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;

	var ca = Rift.ca = Rift.ca || {};

	/* ============================================================
	   Change notification: the page sets ca.onChange(structural)
	   ============================================================ */

	ca.onChange = function () {};

	/** A value changed; structural = the form must be drawn again (e.g. a type changed). */
	function changed(structural) { ca.onChange(!!structural); }
	ca.changed = changed;

	/* ============================================================
	   Group variables offered by the variable picker (set by the Junction part)
	   ============================================================ */

	ca.variables = function () { return []; };

	/* ============================================================
	   Widgets
	   ============================================================ */

	var uid = 0;
	function nextId(prefix) { uid += 1; return (prefix || 'ca') + '-' + uid; }
	ca.nextId = nextId;

	/**
	 * A labelled field.
	 * @param {string} label
	 * @param {Node} control
	 * @param {string|Node} [help]
	 * @param {Object} [o] {req, opt, wide, id}
	 */
	function field(label, control, help, o) {
		o = o || {};
		var lab = el('label.field-label', { 'for': o.id || control.id || null }, [
			label,
			o.req ? el('span.req', { text: 'REQUIRED' }) : null,
			o.opt ? el('span.opt', { text: 'optional' }) : null
		]);
		return el('div.ca-field' + (o.wide ? '.wide' : ''), null, [
			lab,
			control,
			help ? (typeof help === 'string' ? el('div.ca-help', { text: help }) : el('div.ca-help', null, help)) : null
		]);
	}
	ca.field = field;

	/** Help text with **bold** and `code` marks. */
	function rich(text) {
		var nodes = [];
		var re = /(\*\*[^*]+\*\*|`[^`]+`)/g;
		var last = 0, m;
		while ((m = re.exec(text)) !== null) {
			if (m.index > last) { nodes.push(text.slice(last, m.index)); }
			var t = m[0];
			nodes.push(t.charAt(0) === '*' ? el('b', { text: t.slice(2, -2) }) : el('code', { text: t.slice(1, -1) }));
			last = m.index + t.length;
		}
		if (last < text.length) { nodes.push(text.slice(last)); }
		return nodes;
	}
	ca.rich = rich;

	/** An explanation box; paragraphs separated by \n. */
	function note(text, kind) {
		return el('div.ca-note' + (kind ? '.' + kind : ''), null, String(text).split('\n').map(function (p) {
			return el('p', null, rich(p));
		}));
	}
	ca.note = note;

	function grid(children) { return el('div.ca-grid', null, children); }
	ca.grid = grid;

	function sub(text) { return el('div.ca-sub', { text: text }); }
	ca.sub = sub;

	function more(title, children, open) {
		return el('details.ca-more', { open: open ? true : null }, [el('summary', { text: title })].concat(children));
	}
	ca.more = more;

	/**
	 * A text input bound to obj[key].
	 * @param {Object} o {placeholder, vars (offer group variables), list (datalist id), structural,
	 *                    validate(value) -> message|null, numeric, onInput(value), id}
	 */
	function input(obj, key, o) {
		o = o || {};
		var inp = el('input', {
			type: 'text', id: o.id || nextId('in'), value: obj[key] === undefined || obj[key] === null ? '' : String(obj[key]),
			placeholder: o.placeholder || null, list: o.list || null, spellcheck: 'false', autocomplete: 'off',
			inputmode: o.numeric ? 'numeric' : null, 'aria-label': o.label || null
		});
		function check() {
			if (!o.validate) { return; }
			var msg = o.validate(inp.value);
			inp.classList.toggle('invalid', !!msg);
			inp.title = msg || '';
		}
		inp.addEventListener('input', function () {
			obj[key] = inp.value;
			check();
			if (o.onInput) { o.onInput(inp.value); }
			changed(false);
		});
		if (o.structural) {
			inp.addEventListener('change', function () { changed(true); });
		}
		check();
		if (!o.vars) { return inp; }
		return el('div.ca-inline', null, [inp, varPicker(inp)]);
	}
	ca.input = input;

	/** A small "+ VAR" select that inserts a GROUPVARn token into the input. */
	function varPicker(inp) {
		var sel = el('select.ca-varpick', { title: 'Insert a group variable', 'aria-label': 'Insert a group variable' });
		function fill() {
			ui.clear(sel);
			sel.appendChild(el('option', { value: '', text: '+ VAR' }));
			var vars = ca.variables();
			if (!vars.length) {
				sel.appendChild(el('option', { value: '', disabled: true, text: 'no group variables yet (Group panel)' }));
			}
			vars.forEach(function (v) {
				sel.appendChild(el('option', { value: v.name, text: v.name + (v.note ? ' - ' + v.note : v.def ? ' (default ' + v.def + ')' : '') }));
			});
		}
		fill();
		sel.addEventListener('focus', fill);
		sel.addEventListener('mousedown', fill);
		sel.addEventListener('change', function () {
			var t = sel.value;
			sel.value = '';
			if (!t) { return; }
			var s = inp.selectionStart === null || inp.selectionStart === undefined ? inp.value.length : inp.selectionStart;
			var e = inp.selectionEnd === null || inp.selectionEnd === undefined ? s : inp.selectionEnd;
			inp.value = inp.value.slice(0, s) + t + inp.value.slice(e);
			inp.dispatchEvent(new Event('input'));
			inp.focus();
		});
		return sel;
	}

	/** A textarea bound to obj[key]. */
	function area(obj, key, o) {
		o = o || {};
		var ta = el('textarea', { id: o.id || nextId('ta'), placeholder: o.placeholder || null, spellcheck: 'false',
			rows: o.rows || 3, 'aria-label': o.label || null });
		ta.value = obj[key] || '';
		ta.addEventListener('input', function () { obj[key] = ta.value; changed(false); });
		return ta;
	}
	ca.area = area;

	/**
	 * A select bound to obj[key].
	 * @param {Array<{v:string,l:string,g?:string}>} options (g = optgroup label)
	 * @param {Object} [o] {structural (default true), onChange(value)}
	 */
	function select(obj, key, options, o) {
		o = o || {};
		var sel = el('select', { id: o.id || nextId('sel'), 'aria-label': o.label || null });
		var groups = {};
		options.forEach(function (op) {
			var opt = el('option', { value: op.v, text: op.l });
			if (op.g) {
				if (!groups[op.g]) { groups[op.g] = el('optgroup', { label: op.g }); sel.appendChild(groups[op.g]); }
				groups[op.g].appendChild(opt);
			} else {
				sel.appendChild(opt);
			}
		});
		var cur = obj[key] === undefined || obj[key] === null ? '' : String(obj[key]);
		var known = options.some(function (op) { return op.v === cur; });
		if (!known && cur !== '') { sel.appendChild(el('option', { value: cur, text: cur })); }
		sel.value = cur;
		sel.addEventListener('change', function () {
			obj[key] = sel.value;
			if (o.onChange) { o.onChange(sel.value); }
			changed(o.structural !== false);
		});
		return sel;
	}
	ca.select = select;

	/** A checkbox bound to obj[key] (boolean). */
	function check(obj, key, label, o) {
		o = o || {};
		var cb = el('input', { type: 'checkbox', checked: obj[key] ? true : null });
		cb.addEventListener('change', function () {
			obj[key] = cb.checked;
			if (o.onChange) { o.onChange(cb.checked); }
			changed(o.structural !== false);
		});
		return el('label.ca-check', { title: o.title || null }, [cb, el('span', null, rich(label))]);
	}
	ca.check = check;

	/** Segmented buttons bound to obj[key]. */
	function seg(obj, key, options, o) {
		o = o || {};
		return el('div.ca-seg', { role: 'group', 'aria-label': o.label || null }, options.map(function (op) {
			return el('button', {
				type: 'button', 'aria-pressed': String(obj[key]) === op.v ? 'true' : 'false', title: op.title || null,
				on: { click: function () {
					if (String(obj[key]) === op.v) { return; }
					obj[key] = op.v;
					if (o.onChange) { o.onChange(op.v); }
					changed(true);
				} }
			}, op.l);
		}));
	}
	ca.seg = seg;

	/**
	 * Rows of inputs (a small table): arr of objects, cols [{key, placeholder, width, vars, label}].
	 * @param {Object} o {make: () => new row, addLabel, arrow (column index after which an arrow is shown),
	 *                    head: true to show column labels, extra(row) -> Node appended to the row}
	 */
	function rows(arr, cols, o) {
		o = o || {};
		var widths = [];
		cols.forEach(function (c, i) {
			widths.push(c.width || 'minmax(0, 1fr)');
			if (o.arrow === i) { widths.push('18px'); }
		});
		widths.push('auto');
		var tpl = widths.join(' ');
		var box = el('div.ca-rows');
		if (o.head && arr.length) {
			var head = el('div.ca-row', { style: 'grid-template-columns:' + tpl });
			cols.forEach(function (c, i) {
				head.appendChild(el('span.ca-row-head', { text: c.label || '' }));
				if (o.arrow === i) { head.appendChild(el('span')); }
			});
			head.appendChild(el('span'));
			box.appendChild(head);
		}
		arr.forEach(function (row, idx) {
			var r = el('div.ca-row', { style: 'grid-template-columns:' + tpl });
			cols.forEach(function (c, i) {
				var ctl;
				if (c.options) {
					ctl = select(row, c.key, typeof c.options === 'function' ? c.options() : c.options, { structural: false, label: c.label });
				} else {
					ctl = input(row, c.key, { placeholder: c.placeholder, vars: c.vars, validate: c.validate, numeric: c.numeric, label: c.label });
				}
				r.appendChild(ctl);
				if (o.arrow === i) { r.appendChild(el('span.ca-arrow', { text: '\u2192', 'aria-hidden': 'true' })); }
			});
			r.appendChild(el('button.btn.ca-x', { type: 'button', title: 'Remove this row', 'aria-label': 'Remove this row',
				on: { click: function () { arr.splice(idx, 1); changed(true); } } }, '\u2715'));
			box.appendChild(r);
		});
		box.appendChild(el('div', null, el('button.btn.ca-add', { type: 'button', on: { click: function () {
			arr.push(o.make ? o.make() : {});
			changed(true);
		} } }, '+ ' + (o.addLabel || 'Add row'))));
		return box;
	}
	ca.rows = rows;

	/**
	 * Toggle chips for a ';'-list in obj[key] (e.g. FOM modules).
	 * @param {Array<{v,l,g?,title?}>} chips
	 */
	function chips(obj, key, list) {
		var box = el('div.ca-chips');
		var lastGroup = null;
		// a chip may stand for several entries ("a;b;c"): it is on when all of them are listed
		function has(v) {
			var l = splitList(obj[key]);
			return splitList(v).every(function (x) { return l.indexOf(x) >= 0; });
		}
		list.forEach(function (c) {
			if (c.g && c.g !== lastGroup) { box.appendChild(el('span.grp', { text: c.g })); lastGroup = c.g; }
			box.appendChild(el('button', { type: 'button', 'aria-pressed': has(c.v) ? 'true' : 'false', title: c.title || c.v,
				on: { click: function () {
					var l = splitList(obj[key]);
					var parts = splitList(c.v);
					if (has(c.v)) { l = l.filter(function (x) { return parts.indexOf(x) < 0; }); }
					else { parts.forEach(function (x) { if (l.indexOf(x) < 0) { l.push(x); } }); }
					obj[key] = l.join(';');
					changed(true);
				} } }, c.l || c.v));
		});
		return box;
	}
	ca.chips = chips;

	function splitList(s) {
		return String(s || '').split(';').map(function (x) { return x.trim(); }).filter(function (x) { return x; });
	}
	ca.splitList = splitList;

	/* ============================================================
	   Small helpers
	   ============================================================ */

	/** "Blue - Red gateway" -> "blue-red-gateway" */
	ca.slug = function (s, fallback) {
		var t = String(s || '').toLowerCase()
			.replace(/[åä]/g, 'a').replace(/ö/g, 'o')
			.replace(/[^a-z0-9_.-]+/g, '-').replace(/^-+|-+$/g, '').replace(/-{2,}/g, '-');
		return t || fallback || 'config';
	};

	ca.isInt = function (s, min, max) {
		var t = String(s === undefined || s === null ? '' : s).trim();
		if (!/^-?\d+$/.test(t)) { return false; }
		var n = parseInt(t, 10);
		return (min === undefined || n >= min) && (max === undefined || n <= max);
	};

	/** True when the text contains a group variable token (anywhere, as Junction reads it: radio_GROUPVAR1 too). */
	ca.hasVar = function (s) { return /GROUPVAR\d+/.test(String(s || '')); };

	/** Every GROUPVARn token in a text. */
	ca.varsIn = function (s) {
		var out = [], m, re = /GROUPVAR\d+/g;
		while ((m = re.exec(String(s || ''))) !== null) { if (out.indexOf(m[0]) < 0) { out.push(m[0]); } }
		return out;
	};

	/* ============================================================
	   The .properties writer
	   ============================================================ */

	/** Escapes a key: \ space : = and a leading # or !. */
	function escKey(k) {
		var s = String(k).replace(/\\/g, '\\\\').replace(/ /g, '\\ ').replace(/:/g, '\\:').replace(/=/g, '\\=');
		if (/^[#!]/.test(s)) { s = '\\' + s; }
		return s;
	}
	/** Escapes a value: \ (Windows paths!), new lines, and leading spaces. */
	function escVal(v) {
		var s = String(v === undefined || v === null ? '' : v).replace(/\\/g, '\\\\').replace(/\r?\n/g, '\\n');
		return s.replace(/^ /, '\\ ');
	}
	ca.escKey = escKey;
	ca.escVal = escVal;

	/** Builds a settings file line by line. */
	function Props() { this.lines = []; }
	Props.prototype.comment = function (text) {
		var self = this;
		String(text).split('\n').forEach(function (l) { self.lines.push(l ? '# ' + l : '#'); });
		return this;
	};
	Props.prototype.blank = function () {
		if (this.lines.length && this.lines[this.lines.length - 1] !== '') { this.lines.push(''); }
		return this;
	};
	/** key=value; skipped when value is undefined/null (empty string is written). */
	Props.prototype.kv = function (key, value, comment) {
		if (value === undefined || value === null) { return this; }
		if (comment) { this.comment(comment); }
		this.lines.push(escKey(key) + '=' + escVal(value));
		return this;
	};
	/** A commented-out example. */
	Props.prototype.hint = function (key, value, comment) {
		if (comment) { this.comment(comment); }
		this.lines.push('# ' + escKey(key) + '=' + escVal(value));
		return this;
	};
	Props.prototype.raw = function (line) { this.lines.push(line); return this; };
	Props.prototype.text = function () {
		var l = this.lines.slice();
		while (l.length && l[l.length - 1] === '') { l.pop(); }
		return l.join('\n') + '\n';
	};
	ca.Props = Props;

	/**
	 * "key=value" lines typed by the user (advanced extras) -> [{key, value}]; # comments and
	 * empty lines are skipped. The value is kept as typed (already in file syntax).
	 */
	ca.parseExtra = function (text) {
		var out = [];
		String(text || '').split(/\r?\n/).forEach(function (line) {
			var t = line.trim();
			if (!t || t.charAt(0) === '#' || t.charAt(0) === '!') { return; }
			var m = /^([^=:\s]+)\s*[=:]\s*(.*)$/.exec(t);
			out.push(m ? { key: m[1], value: m[2], line: t } : { key: null, value: null, line: t });
		});
		return out;
	};

	/* ============================================================
	   The files panel
	   ============================================================ */

	function highlight(text) {
		var pre = el('pre.ca-code');
		text.replace(/\n$/, '').split('\n').forEach(function (line, i) {
			if (i > 0) { pre.appendChild(document.createTextNode('\n')); }
			if (/^\s*[#!]/.test(line)) { pre.appendChild(el('span.c', { text: line })); return; }
			var m = /^((?:\\.|[^=:\\])+?)(=)(.*)$/.exec(line);
			if (!m) { pre.appendChild(document.createTextNode(line)); return; }
			pre.appendChild(el('span.k', { text: m[1] }));
			pre.appendChild(document.createTextNode('='));
			var parts = m[3].split(/(GROUPVAR\d+)/);
			parts.forEach(function (p) {
				if (!p) { return; }
				pre.appendChild(/^GROUPVAR\d+$/.test(p) ? el('span.gv', { text: p }) : el('span.v', { text: p }));
			});
		});
		return pre;
	}

	/** Copies text; resolves true when it worked. Falls back to a hidden textarea. */
	function copyText(text) {
		if (navigator.clipboard && window.isSecureContext) {
			return navigator.clipboard.writeText(text).then(function () { return true; }, function () { return legacyCopy(text); });
		}
		return Promise.resolve(legacyCopy(text));
	}
	function legacyCopy(text) {
		var ta = el('textarea', { style: 'position:fixed;left:-9999px;top:0', 'aria-hidden': 'true' });
		ta.value = text;
		document.body.appendChild(ta);
		ta.select();
		var ok = false;
		try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
		document.body.removeChild(ta);
		return ok;
	}
	ca.copyText = copyText;

	function confirmButton(btn, ok, label) {
		var old = btn.textContent;
		btn.textContent = ok ? label : 'Select and copy by hand';
		if (ok) { btn.classList.add('ca-done'); }
		setTimeout(function () { btn.textContent = old; btn.classList.remove('ca-done'); }, 1500);
	}

	function download(name, text) {
		var blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
		var a = el('a', { href: URL.createObjectURL(blob), download: name });
		document.body.appendChild(a);
		a.click();
		setTimeout(function () { URL.revokeObjectURL(a.href); document.body.removeChild(a); }, 1000);
	}
	ca.download = download;

	/**
	 * Draws the result: issues, files, next steps.
	 * @param {Node} host
	 * @param {{issues:Array<{level,text}>, files:Array<{path,content,about?,kind?}>, steps?:Array<string>, empty?:string}} r
	 */
	/* ============================================================
	   Roles: what kind of file a box on the left ends up in.
	   The same name and colour are used on the form ("Goes into")
	   and on the files panel, so the two can be matched at a glance.
	   ============================================================ */

	var ROLES = {
		gateway: { l: 'Gateway file', cls: 'r-gw', d: 'one per gateway: its endpoints, its router, and its own values' },
		epkit: { l: 'Endpoint kit', cls: 'r-ep', d: 'a shared endpoint file; only written when you choose "Also save as a kit"' },
		rtkit: { l: 'Router kit', cls: 'r-rt', d: 'a shared router file; only written when you choose "Also save as a router kit"' },
		ds: { l: 'Datasource configuration', cls: 'r-ds', d: 'one per datasource: what Rift joins or listens to' },
		server: { l: 'Server settings', cls: 'r-sv', d: 'port, certificate, logs, Junction on/off' },
		lp: { l: 'Object list attributes', cls: 'r-lp', d: 'which columns the object list shows' },
		existing: { l: 'Existing kit', cls: 'r-ex', d: 'already a file (delivered or on the server): named, not written' }
	};
	ca.ROLES = ROLES;

	/** A small role label, e.g. [GATEWAY FILE]. */
	function roleTag(role) {
		var r = ROLES[role] || { l: role, cls: '' };
		return el('span.ca-role.' + r.cls, { text: r.l });
	}
	ca.roleTag = roleTag;

	/** Scrolls the files panel to a file and flashes it. */
	ca.showFile = function (path) {
		var card = document.querySelector('.ca-file[data-path="' + String(path).replace(/"/g, '\\"') + '"]');
		if (!card) { ui.toast('That file is not in the list: fix the problems shown there first.', 'info'); return; }
		card.scrollIntoView({ behavior: 'smooth', block: 'start' });
		card.classList.remove('ca-flash');
		void card.offsetWidth;
		card.classList.add('ca-flash');
	};

	/** The page sets this: show the box a file comes from (switching gateway / datasource tab if needed). */
	ca.reveal = function () {};

	/**
	 * "Goes into" line of a box on the form.
	 * @param {Array<{role, path, note?}>} targets  files this box ends up in; role 'existing' = named, not written
	 * @param {string} [lead] text before the files (default "Goes into")
	 */
	ca.goesInto = function (targets, lead) {
		return el('div.ca-goes', null, [
			el('span.ca-goes-lead', { text: (lead || 'Goes into') + ' →' })
		].concat(targets.map(function (t) {
			var r = ROLES[t.role] || { l: t.role, cls: '' };
			var inner = [el('span.ca-role.' + r.cls, { text: r.l }), el('code', { text: t.path })];
			if (t.note) { inner.push(el('span.ca-goes-note', { text: t.note })); }
			if (t.role === 'existing') {
				return el('span.ca-goes-file.' + r.cls, { title: 'This kit is already a file: the gateway file only names it.' }, inner);
			}
			return el('button.ca-goes-file.' + r.cls, { type: 'button', title: 'Show this file on the right',
				on: { click: function () { ca.showFile(t.path); } } }, inner.concat([el('span.ca-goes-show', { text: 'show ↗' })]));
		})));
	};

	/**
	 * Draws the result: legend, issues, files, next steps.
	 * @param {Node} host
	 * @param {{issues:Array<{level,text}>, files:Array<{path,content,about?,kind?,role?,from?:{text,ref}}>,
	 *          steps?:Array<string>, legend?:Array<string>, empty?:string}} r
	 */
	ca.renderOutput = function (host, r) {
		ui.clear(host);
		if (r.legend && r.legend.length) {
			host.appendChild(el('div.ca-legend', null, [
				el('div.ca-legend-lead', null, rich('Each file below is made from the boxes on the left; the coloured label says which kind it is. **Show in the form** goes to its box, and **show ↗** on a box comes back here.'))
			].concat(r.legend.map(function (role) {
				var x = ROLES[role];
				return el('div.ca-legend-row', null, [roleTag(role), el('span', { text: x ? x.d : '' })]);
			}))));
		}
		var issues = r.issues || [];
		if (issues.length) {
			host.appendChild(el('div.ca-issues', null, issues.map(function (i) {
				return el('div.ca-issue.' + i.level, null, [
					el('span.lvl', { text: i.level === 'err' ? 'FIX' : i.level === 'warn' ? 'CHECK' : 'NOTE' }),
					el('span', null, rich(i.text))
				]);
			})));
		} else if (r.files && r.files.length) {
			host.appendChild(el('div.ca-ok-line', { text: 'No problems found' }));
		}
		if (!r.files || !r.files.length) {
			host.appendChild(el('div.ca-empty', { text: r.empty || 'Nothing to create yet.' }));
		}
		(r.files || []).forEach(function (f, n) {
			var name = f.path.split('/').pop();
			var copyBtn = el('button.btn', { type: 'button', title: 'Copy the file content' }, 'Copy');
			copyBtn.addEventListener('click', function () {
				copyText(f.content).then(function (ok) { confirmButton(copyBtn, ok, 'Copied'); });
			});
			var pathBtn = el('button.btn', { type: 'button', title: 'Copy the path' }, 'Path');
			pathBtn.addEventListener('click', function () {
				copyText(f.path).then(function (ok) { confirmButton(pathBtn, ok, 'Copied'); });
			});
			var kind = f.kind || 'new';
			var role = ROLES[f.role] || null;
			host.appendChild(el('div.ca-file' + (role ? '.' + role.cls : ''), { 'data-path': f.path }, [
				el('div.ca-file-head', null, [
					el('span.ca-file-no', { text: String(n + 1) }),
					f.role ? roleTag(f.role) : null,
					el('span.ca-file-kind.' + kind, { text: kind === 'replace' ? 'REPLACES A FILE' : kind === 'edit' ? 'EDIT' : 'NEW FILE' }),
					el('span.ca-file-acts', null, [
						copyBtn, pathBtn,
						el('button.btn', { type: 'button', title: 'Download as ' + name,
							on: { click: function () { download(name, f.content); } } }, 'Download')
					]),
					el('span.ca-file-path', { text: f.path })
				]),
				f.from ? el('div.ca-file-from', null, [
					el('span.k', { text: 'Comes from' }),
					el('span', null, rich(f.from.text)),
					f.from.ref ? el('button.btn', { type: 'button', title: 'Go to its box in the form',
						on: { click: function () { ca.reveal(f.from.ref); } } }, '← Show in the form') : null
				]) : null,
				f.about ? el('div.ca-file-about', null, rich(f.about)) : null,
				highlight(f.content)
			]));
		});
		if (r.steps && r.steps.length) {
			host.appendChild(sub('Then'));
			host.appendChild(el('ol.ca-steps', null, r.steps.map(function (s) { return el('li', null, rich(s)); })));
		}
	};

	/* ============================================================
	   Remembered state (this browser only; the page works without it)
	   ============================================================ */

	var STORE = 'simweaver-config-assistant-v1';
	ca.load = function () {
		try {
			var raw = window.localStorage.getItem(STORE);
			return raw ? JSON.parse(raw) : null;
		} catch (e) { return null; }
	};
	ca.save = function (state) {
		try { window.localStorage.setItem(STORE, JSON.stringify(state)); } catch (e) { /* storage blocked */ }
	};
	ca.forget = function () {
		try { window.localStorage.removeItem(STORE); } catch (e) { /* ignore */ }
	};
})(window, document);
