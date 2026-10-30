/* ============================================================
   SimWeaver Rift - object list (middle panel).

   - Paged list view (GET .../objects, 200 per page, more on scroll).
   - Rows expand to the full view (GET .../objects/{iid}): all attributes as value trees,
     raw hex on demand, and related objects (references / referenced by).
   - Live: rows visible on screen are subscribed as "list", expanded ones as "full".
     New objects are counted in a "+N new" banner instead of jumping the list.
   - focus(target): expands an object; when it is not in the current list it is
     shown pinned above the list.

   var list = new Rift.ObjectList({ dsid, stream, els: {...}, onClassClick, onFocusChange });
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;
	var vv = Rift.valueView;

	var PAGE = 200;
	var MAX_VISIBLE = 100;   // object topics for visible rows (URL length, server work)

	function ObjectList(opts) {
		this.dsid = opts.dsid;
		this.stream = opts.stream;
		this.els = opts.els;          // box, scroller, search, sort, related, banner, meta, pinned
		this.opts = opts;
		this.filter = { 'class': [], subclasses: true, q: '', related: false };
		this.sort = 'label';
		this.items = [];
		this.byId = {};
		this.rows = {};
		this.total = 0;
		this.loading = false;
		this.token = 0;
		this.queue = [];
		this.snapSeq = 0;
		this.expanded = new Set();
		this.full = {};
		this.visible = new Set();
		this.newCount = 0;
		this.pinned = null;           // iid shown above the list
		this.selected = null;         // iid highlighted
		this.wire();
	}

	/* ---------- setup ---------- */

	ObjectList.prototype.wire = function () {
		var self = this, e = this.els;
		var t = null;
		e.search.addEventListener('input', function () {
			clearTimeout(t);
			t = setTimeout(function () {
				var q = e.search.value.trim();
				if (q !== self.filter.q) { self.filter.q = q; self.reload(); }
			}, 300);
		});
		e.sort.addEventListener('change', function () { self.sort = e.sort.value; self.reload(); });
		e.related.addEventListener('change', function () { self.filter.related = e.related.checked; self.reload(); });
		e.banner.addEventListener('click', function () { self.reload(); });
		this.atTop = true;
		e.scroller.addEventListener('scroll', function () { self.atTop = e.scroller.scrollTop < 10; }, { passive: true });

		if ('IntersectionObserver' in window) {
			this.io = new IntersectionObserver(function (entries) {
				var changed = false;
				entries.forEach(function (en) {
					var id = en.target.getAttribute('data-id');
					if (en.isIntersecting) { if (!self.visible.has(id)) { self.visible.add(id); changed = true; } }
					else if (self.visible.delete(id)) { changed = true; }
				});
				if (changed) { self.stream.refreshTopics(); }
			}, { root: e.scroller, rootMargin: '120px 0px' });
			this.moreIo = new IntersectionObserver(function (entries) {
				if (entries.some(function (en) { return en.isIntersecting; })) { self.loadMore(); }
			}, { root: e.scroller, rootMargin: '300px 0px' });
		}
		setInterval(function () { self.tickAges(); }, 1000);
	};

	/** Instance ids this panel wants live updates for: visible rows, expanded and pinned. */
	ObjectList.prototype.liveIds = function () {
		var ids = Array.from(this.visible).slice(0, MAX_VISIBLE);
		var add = function (id) { if (id && ids.indexOf(id) === -1) { ids.push(id); } };
		this.expanded.forEach(add);
		add(this.pinned);
		return ids;
	};

	/** Client-side check of a newly discovered object against the current filter. */
	ObjectList.prototype.matchesFilter = function (o) {
		var f = this.filter;
		if (f['class'].length && !f['class'].some(function (c) {
			return o.classnameq === c || String(o.classnameq).indexOf(c + '.') === 0;
		})) { return f.related; }   // with "+ related" it may still be a referencing object
		if (!f.q) { return true; }
		var q = f.q.toLowerCase();
		var hay = [o.label, o.instancename].concat((o.attributes || []).map(function (a) { return vv.text(a.value); }));
		(o.attributes || []).forEach(function (a) {         // 0.11.1: the entity type's name and country too
			var et = a.value && a.value.entitytype;
			if (et) { hay.push(et.text, et.context, et.country, et.countryname); }
		});
		return hay.some(function (t) { return t && String(t).toLowerCase().indexOf(q) !== -1; });
	};

	ObjectList.prototype.setClasses = function (classes) {
		this.filter['class'] = classes || [];
		this.reload();
	};

	/* ---------- loading ---------- */

	ObjectList.prototype.reload = function () {
		var self = this;
		var token = ++this.token;
		this.loading = true;
		this.queue = [];
		this.newCount = 0;
		this.updateBanner();
		this.items = [];
		this.byId = {};
		this.rows = {};
		this.visible.clear();
		if (this.io) { this.io.disconnect(); }
		if (this.moreIo) { this.moreIo.disconnect(); }
		var box = ui.clear(this.els.box);
		box.appendChild(ui.loadingRow('Acquiring objects\u2026'));
		this.stream.refreshTopics();

		return Rift.api.fetchObjects(this.dsid, this.params(0)).then(function (reply) {
			if (token !== self.token) { return; }
			ui.clear(box);
			self.total = reply.total || 0;
			self.list = el('ul.obj-list', { role: 'list' });
			box.appendChild(self.list);
			self.append(reply.objects || []);
			self.renderEmpty();
			self.loading = false;
			self.drain();
			self.updateMeta();
			// Expanded objects that are no longer listed are closed.
			Array.from(self.expanded).forEach(function (id) {
				if (!self.byId[id]) { self.expanded.delete(id); if (id !== self.pinned) { delete self.full[id]; } }
			});
			self.stream.refreshTopics();
		}, function (err) {
			if (token !== self.token) { return; }
			self.loading = false;
			ui.clear(box).appendChild(ui.errorRow('Could not load objects', err, function () { self.reload(); }));
		});
	};

	ObjectList.prototype.params = function (offset) {
		return { 'class': this.filter['class'], q: this.filter.q, related: this.filter.related,
			sort: this.sort, offset: offset, limit: PAGE };
	};

	ObjectList.prototype.loadMore = function () {
		var self = this;
		if (this.loading || this.loadingMore || this.items.length >= this.total) { return; }
		this.loadingMore = true;
		var token = this.token;
		Rift.api.fetchObjects(this.dsid, this.params(this.items.length)).then(function (reply) {
			self.loadingMore = false;
			if (token !== self.token) { return; }
			self.append(reply.objects || []);
			self.total = reply.total || self.total;
			self.updateMeta();
		}, function () { self.loadingMore = false; });
	};

	ObjectList.prototype.append = function (objects) {
		var self = this;
		if (this.sentinel && this.sentinel.parentNode) { this.sentinel.parentNode.removeChild(this.sentinel); }
		objects.forEach(function (o) {
			if (!o || !o.instanceid || self.byId[o.instanceid]) { return; }
			self.items.push(o);
			self.byId[o.instanceid] = o;
			self.list.appendChild(self.renderRow(o));
		});
		if (this.items.length < this.total) {
			this.sentinel = el('li.obj-more', { text: 'Loading more\u2026 (' + this.items.length + ' of ' + this.total + ')' });
			this.list.appendChild(this.sentinel);
			if (this.moreIo) { this.moreIo.observe(this.sentinel); }
		}
	};

	ObjectList.prototype.renderEmpty = function () {
		if (this.items.length) { return; }
		var f = this.filter;
		var filtered = f['class'].length || f.q;
		this.list.appendChild(el('li', null, ui.emptyBlock(
			filtered ? 'No objects match the filter' : 'No object instances discovered yet',
			filtered ? 'Clear the class selection or the search to see more.' : 'Objects appear here as soon as the federation publishes them.')));
	};

	ObjectList.prototype.updateMeta = function () {
		var m = ui.clear(this.els.meta);
		m.appendChild(el('span', { text: this.total.toLocaleString('sv-SE') + ' MATCH' }));
		if (this.items.length < this.total) {
			m.appendChild(el('span.dim', { text: ' \u00B7 ' + this.items.length + ' loaded' }));
		}
	};

	ObjectList.prototype.updateBanner = function () {
		var b = this.els.banner;
		b.hidden = this.newCount === 0;
		b.textContent = '\u25B2 ' + this.newCount + ' new object' + (this.newCount === 1 ? '' : 's') + ' \u2014 show';
	};

	/* ---------- rows ---------- */

	ObjectList.prototype.renderRow = function (o) {
		var self = this;
		var id = o.instanceid;
		var head = el('div.obj-head', {
			role: 'button', tabindex: '0', 'aria-expanded': this.expanded.has(id) ? 'true' : 'false',
			on: {
				click: function () { self.toggle(id); },
				keydown: function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); self.toggle(id); } }
			}
		});
		var li = el('li.obj-row' + (o.state === 'removed' ? '.removed' : ''), { 'data-id': id }, [head]);
		this.rows[id] = li;
		this.fillHead(o, head);
		if (this.expanded.has(id)) { this.renderBody(id, li); }
		if (id === this.selected) { li.classList.add('selected'); }
		if (this.io) { this.io.observe(li); }
		return li;
	};

	ObjectList.prototype.fillHead = function (o, head) {
		var self = this;
		ui.clear(head);
		head.appendChild(el('span.obj-caret', { 'aria-hidden': 'true', text: this.expanded.has(o.instanceid) ? '\u25BE' : '\u25B8' }));
		head.appendChild(el('span.lamp' + (o.state === 'removed' ? '.off' : ''), { 'aria-hidden': 'true' }));
		head.appendChild(el('span.obj-label', { text: o.label || o.instancename || o.instanceid, title: o.instancename }));
		head.appendChild(el('button.obj-class', {
			type: 'button', title: 'Filter on ' + o.classnameq,
			on: { click: function (e) { e.stopPropagation(); if (self.opts.onClassClick) { self.opts.onClassClick(o.classnameq); } } }
		}, o.classname));
		var attrs = el('span.obj-attrs');
		(o.attributes || []).forEach(function (a) {
			attrs.appendChild(el('span.oa', { title: a.name + ' (' + a.datatype + ')' }, [
				el('span.oa-name', { text: a.name }), vv.inline(a.value, 3)
			]));
		});
		head.appendChild(attrs);
		if (o.state === 'removed') { head.appendChild(el('span.obj-state', { text: 'REMOVED' })); }
		head.appendChild(ageNode(o.lastupdated, 'Last update'));
	};

	function ageNode(iso, label) {
		var t = Date.parse(iso);
		return el('span.age', { 'data-t': isNaN(t) ? '' : String(t), title: (label || 'Updated') + ' ' + (iso || '\u2014') }, '\u2014');
	}

	function ageText(ms) {
		var s = Math.max(0, Math.round(ms / 1000));
		if (s < 60) { return s + 's'; }
		if (s < 3600) { return Math.floor(s / 60) + 'm'; }
		if (s < 86400) { return Math.floor(s / 3600) + 'h'; }
		return Math.floor(s / 86400) + 'd';
	}

	ObjectList.prototype.tickAges = function () {
		var now = this.stream.serverNow();
		var root = this.els.scroller.parentNode || document;
		Array.prototype.forEach.call(root.querySelectorAll('.age[data-t]'), function (n) {
			var t = Number(n.getAttribute('data-t'));
			if (!t) { return; }
			var d = now - t;
			n.textContent = ageText(d);
			n.classList.toggle('fresh', d < 5000);
			n.classList.toggle('stale', d > 60000);
		});
	};

	ObjectList.prototype.flash = function (node) { ui.pulse(node); };

	/* ---------- expand / full view ---------- */

	ObjectList.prototype.toggle = function (id) {
		if (this.expanded.has(id)) {
			this.expanded.delete(id);
			delete this.full[id];
			var li = this.rows[id];
			if (li) {
				var body = li.querySelector('.obj-body');
				if (body) { li.removeChild(body); }
				this.fillHead(this.byId[id], li.firstChild);
				li.firstChild.setAttribute('aria-expanded', 'false');
			}
		} else {
			this.expanded.add(id);
			var row = this.rows[id];
			if (row) {
				this.fillHead(this.byId[id], row.firstChild);
				row.firstChild.setAttribute('aria-expanded', 'true');
				this.renderBody(id, row);
			}
		}
		this.stream.refreshTopics();
	};

	/** Loads (or reuses) the full view and renders it into the row's body. */
	ObjectList.prototype.renderBody = function (id, container) {
		var self = this;
		var body = container.querySelector(':scope > .obj-body');
		if (!body) { body = el('div.obj-body'); container.appendChild(body); }
		if (this.full[id]) { this.fillBody(id, body); return; }
		ui.clear(body).appendChild(ui.loadingRow('Acquiring full object\u2026'));
		Rift.api.fetchObject(this.dsid, id, 'full').then(function (reply) {
			if (!self.expanded.has(id) && self.pinned !== id) { return; }
			self.full[id] = reply.object;
			self.fillBody(id, body);
		}, function (err) {
			ui.clear(body).appendChild(err.status === 404
				? ui.emptyBlock('Object no longer exists', 'It was removed from the federation.')
				: ui.errorRow('Could not load the object', err, function () { self.renderBody(id, container); }));
		});
	};

	ObjectList.prototype.fillBody = function (id, body) {
		var self = this;
		var o = this.full[id];
		var openPaths = vv.openPathsIn(body);
		var showRaw = new Set();
		Array.prototype.forEach.call(body.querySelectorAll('.at-row.raw-open'), function (r) { showRaw.add(r.getAttribute('data-name')); });
		ui.clear(body);

		body.appendChild(el('div.obj-meta', null, [
			metaItem('INSTANCE', el('code', { text: o.instancename || '\u2014' })),
			metaItem('CLASS', el('code', { text: o.classnameq, title: o.classnameq })),
			o.producer ? metaItem('PRODUCER', el('span', { text: o.producer })) : null,
			metaItem('DISCOVERED', el('span', { text: Rift.ui.zulu(o.discovered) })),
			metaItem('UPDATES', el('span.mi-updates', { text: String(o.updatecount != null ? o.updatecount : '\u2014') })),
			el('button.btn.obj-logfocus', {
				type: 'button', title: 'Show only interactions that involve this object',
				on: { click: function () { if (self.opts.onFocusLog) { self.opts.onFocusLog({ instanceid: o.instanceid, label: o.label, classname: o.classname }); } } }
			}, '\u2398 Interactions'),
			// NEW (map): objects with a position (or carried by one) can be shown on the map
			self.opts.onShowOnMap && hasPosition(o) ? el('button.btn.obj-onmap', {
				type: 'button', title: 'Show it on the map (also for a radio or mounted part: its host)',
				on: { click: function () { self.opts.onShowOnMap({ instanceid: o.instanceid, label: o.label, classname: o.classname }); } }
			}, '\u2316 On map') : null
		]));
		if (o.state === 'removed') {
			body.appendChild(el('div.obj-removed', { text: 'Removed from the federation' + (o.removed ? ' at ' + Rift.ui.zulu(o.removed) : '') + '.' }));
		}

		var table = el('div.at-table', { role: 'table', 'aria-label': 'Attributes' });
		(o.attributes || []).forEach(function (a) {
			table.appendChild(attrRow(a, openPaths, showRaw.has(a.name)));
		});
		if (!(o.attributes || []).length) { table.appendChild(el('div.dim', { text: 'No attribute values received yet.' })); }
		body.appendChild(table);
		body.appendChild(this.renderRelated(o.related));
		this.tickAges();
	};

	function attrRow(a, openPaths, rawOpen) {
		var row = el('div.at-row' + (rawOpen ? '.raw-open' : ''), { role: 'row', 'data-name': a.name });
		row.appendChild(el('div.at-name', { role: 'cell', title: a.datatype }, [a.name, el('span.at-type', { text: a.datatype })]));
		row.appendChild(el('div.at-value', { role: 'cell' }, vv.tree(a.value, null, a.name, openPaths)));
		row.appendChild(el('div.at-side', { role: 'cell' }, [
			ageNode(a.lastupdated, 'Attribute updated'),
			a.valueRaw !== undefined ? el('button.at-hex', {
				type: 'button', title: 'Show raw bytes',
				on: { click: function () { row.classList.toggle('raw-open'); } }
			}, 'HEX') : null
		]));
		if (a.valueRaw !== undefined) {
			row.appendChild(el('div.at-raw', { role: 'cell' }, [
				el('code', { text: hexGroups(a.valueRaw) }),
				el('span.dim', { text: ' ' + (a.rawsize != null ? a.rawsize + ' B' : '') + (a.rawtruncated ? ' (cut)' : '') })
			]));
		}
		return row;
	}

	/** NEW (map): a Spatial (own position) or a host reference (shown at the host). */
	function hasPosition(o) {
		return (o.attributes || []).some(function (a) {
			return a.name === 'Spatial' || a.name === 'HostObjectIdentifier' || a.name === 'IsPartOf';
		});
	}

	function metaItem(label, node) {
		return el('span.mi', null, [el('span.mi-label', { text: label }), node]);
	}

	function hexGroups(hex) {
		return String(hex || '').replace(/(.{8})/g, '$1 ').trim();
	}

	/** References and referenced-by, each target/source shown once with the attributes used. */
	ObjectList.prototype.renderRelated = function (rel) {
		var self = this;
		var wrap = el('div.rel');
		if (!rel) { return wrap; }

		function group(entries, side) {
			var byKey = {}, order = [];
			(entries || []).forEach(function (e) {
				var obj = e[side];
				var key = obj ? obj.instanceid : 'unresolved:' + e.key;
				if (!byKey[key]) { byKey[key] = { obj: obj, key: e.key, kind: e.kind, via: [] }; order.push(key); }
				if (byKey[key].via.indexOf(e.path || e.attribute) === -1) { byKey[key].via.push(e.path || e.attribute); }
			});
			return order.map(function (k) { return byKey[k]; });
		}

		function card(g) {
			if (!g.obj) {
				return el('li.rel-card.unresolved', null, [
					el('span.lamp.off', { 'aria-hidden': 'true' }),
					el('span.rel-label', { text: g.key }),
					el('span.rel-class', { text: 'not discovered' }),
					el('span.rel-via', { text: 'via ' + g.via.join(', ') })
				]);
			}
			var o = g.obj;
			return el('li.rel-card', null, el('button.rel-open', {
				type: 'button', title: 'Go to ' + (o.label || o.instanceid),
				on: { click: function () { self.focus({ instanceid: o.instanceid, label: o.label, classname: o.classname }); } }
			}, [
				el('span.lamp', { 'aria-hidden': 'true' }),
				el('span.rel-label', { text: o.label || o.instancename }),
				el('span.rel-class', { text: o.classname }),
				el('span.rel-attrs', null, (o.attributes || []).slice(0, 3).map(function (a) {
					return el('span.oa', null, [el('span.oa-name', { text: a.name }), el('span', { text: vv.text(a.value) })]);
				})),
				el('span.rel-via', { text: 'via ' + g.via.join(', ') })
			]));
		}

		var out = group(rel.outgoing, 'target');
		var inc = group(rel.incoming, 'source');
		if (!out.length && !inc.length) { return wrap; }
		if (out.length) {
			wrap.appendChild(el('div.rel-head', { text: 'References \u00B7 ' + out.length }));
			wrap.appendChild(el('ul.rel-list', null, out.map(card)));
		}
		if (inc.length) {
			var more = (rel.incomingtotal || 0) - (rel.incoming || []).length;
			wrap.appendChild(el('div.rel-head', { text: 'Referenced by \u00B7 ' + inc.length + (more > 0 ? ' (+' + more + ' more)' : '') }));
			wrap.appendChild(el('ul.rel-list', null, inc.map(card)));
		}
		return wrap;
	};

	/* ---------- focus (cross-links) ---------- */

	/** Expands and scrolls to an object; pins it above the list when it is not listed. */
	ObjectList.prototype.focus = function (target) {
		var id = target.instanceid;
		if (this.selected && this.rows[this.selected]) { this.rows[this.selected].classList.remove('selected'); }
		this.selected = id;
		if (this.byId[id]) {
			this.unpin();
			if (!this.expanded.has(id)) { this.toggle(id); }
			var li = this.rows[id];
			li.classList.add('selected');
			li.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
			this.flash(li);
			return;
		}
		this.pin(target);
	};

	ObjectList.prototype.pin = function (target) {
		var self = this;
		var id = target.instanceid;
		this.pinned = id;
		this.pinLabel = target.label;
		this.pinClass = target.classname;
		var box = ui.clear(this.els.pinned);
		box.hidden = false;
		var holder = el('div.pin-card', { 'data-id': id });
		box.appendChild(el('div.pin-head', null, [
			el('span.pin-title', { text: 'Linked object \u00B7 not in the current list' }),
			el('button.btn', { type: 'button', on: { click: function () { self.unpin(); } } }, '\u2715 Close')
		]));
		holder.appendChild(el('div.obj-head.static', null, [
			el('span.lamp', { 'aria-hidden': 'true' }),
			el('span.obj-label', { text: target.label || id }),
			el('span.obj-class.static', { text: target.classname || '' })
		]));
		box.appendChild(holder);
		delete this.full[id];
		this.renderBody(id, holder);
		this.stream.refreshTopics();
		this.els.scroller.scrollTop = 0;
	};

	ObjectList.prototype.unpin = function () {
		if (!this.pinned) { return; }
		if (!this.expanded.has(this.pinned)) { delete this.full[this.pinned]; }
		this.pinned = null;
		ui.clear(this.els.pinned).hidden = true;
		this.stream.refreshTopics();
	};

	/* ---------- live events (XrSseHub patches) ---------- */

	/**
	 * Patch of topic "ds:<id>:objects": { "<iid>": listViewObject | null }.
	 * null = removed; an unknown id = discovered; a known id = its list view changed.
	 */
	ObjectList.prototype.onObjectsPatch = function (patch) {
		if (this.loading) { this.queue.push(['objects', patch]); return; }
		var self = this;
		Object.keys(patch).forEach(function (id) {
			var v = patch[id];
			if (v === null) { self.applyRemoved(id); }
			else if (v && typeof v === 'object') { self.applyListView(id, v); }
		});
	};

	/**
	 * Patch of topic "obj:<ds>:<iid>": changed attributes by name (full attribute JSON)
	 * plus "@rev", "@lastupdated", "@updatecount", optional "@state", "@related".
	 */
	ObjectList.prototype.onObjectPatch = function (id, patch) {
		if (this.loading) { this.queue.push(['object', id, patch]); return; }
		var attrs = [];
		Object.keys(patch).forEach(function (k) {
			if (k.charAt(0) !== '@' && patch[k] && typeof patch[k] === 'object') {
				var a = patch[k];
				if (!a.name) { a.name = k; }
				attrs.push(a);
			}
		});
		if (patch['@state'] === 'removed') { this.applyRemoved(id); }
		if (attrs.length || patch['@lastupdated']) {
			this.applyUpdate(id, { rev: patch['@rev'], lastupdated: patch['@lastupdated'],
				updatecount: patch['@updatecount'], view: 'full', attributes: attrs });
		}
		if (patch['@related'] && this.full[id]) {
			this.full[id].related = patch['@related'];
			this.refreshBody(id);
		}
	};

	ObjectList.prototype.drain = function () {
		var q = this.queue, self = this;
		this.queue = [];
		q.forEach(function (e) {
			if (e[0] === 'objects') { self.onObjectsPatch(e[1]); } else { self.onObjectPatch(e[1], e[2]); }
		});
	};

	ObjectList.prototype.applyListView = function (id, v) {
		var o = this.byId[id];
		if (o) {
			if (v.rev && o.rev && v.rev <= o.rev) { return; }
			var keepState = o.state === 'removed' ? 'removed' : v.state;
			Object.keys(v).forEach(function (k) { o[k] = v[k]; });
			o.state = keepState || 'active';
			var li = this.rows[id];
			if (li) { this.fillHead(o, li.firstChild); this.flash(li.firstChild); }
			return;
		}
		if (!this.matchesFilter(v)) { return; }
		if (this.sort === 'discovered' && this.atTop && this.list) {
			this.items.unshift(v);
			this.byId[id] = v;
			this.list.insertBefore(this.renderRow(v), this.list.firstChild);
			this.total++;
			this.updateMeta();
			this.flash(this.rows[id]);
		} else {
			this.newCount++;
			this.updateBanner();
		}
	};

	ObjectList.prototype.applyRemoved = function (id) {
		var o = this.byId[id];
		if (o && o.state !== 'removed') {
			o.state = 'removed';
			this.rows[id].classList.add('removed');
			this.fillHead(o, this.rows[id].firstChild);
		}
		if (this.full[id] && this.full[id].state !== 'removed') {
			this.full[id].state = 'removed';
			this.full[id].removed = new Date(this.stream.serverNow()).toISOString();
			this.refreshBody(id);
		}
	};

	/**
	 * After a topic switch: rows that just became live may have missed changes while they
	 * were not subscribed, so their list view is fetched again (GET .../objects?ids=...).
	 */
	ObjectList.prototype.refreshIds = function (ids) {
		var self = this;
		ids = ids.filter(function (id) { return self.byId[id]; });
		if (!ids.length) { return; }
		Rift.api.fetchObjects(this.dsid, { ids: ids, limit: ids.length }).then(function (reply) {
			(reply.objects || []).forEach(function (o) { self.applyListView(o.instanceid, o); });
		}, function () { /* rows keep their snapshot; the next change will patch them */ });
	};

	function mergeAttributes(target, changed) {
		var at = target.attributes || (target.attributes = []);
		(changed || []).forEach(function (c) {
			for (var i = 0; i < at.length; i++) {
				if (at[i].name === c.name) { at[i] = c; return; }
			}
			at.push(c);
		});
	}

	ObjectList.prototype.applyUpdate = function (id, d) {
		var o = this.byId[id];
		if (o && (!d.rev || !o.rev || d.rev > o.rev)) {
			var listNames = (o.attributes || []).map(function (a) { return a.name; });
			// Only attributes the list view already shows (a full update carries more).
			var changed = (d.attributes || []).filter(function (a) { return listNames.indexOf(a.name) !== -1; });
			mergeAttributes(o, changed.map(shallowAttr));
			// A patch without @rev (computed values such as DRSpatial: only time passed) keeps the rev
			if (d.rev) { o.rev = d.rev; }
			o.lastupdated = d.lastupdated || o.lastupdated;
			var li = this.rows[id];
			if (li) {
				if (changed.length) { this.fillHead(o, li.firstChild); this.flash(li.firstChild); }
				else { this.touchAge(li.firstChild, o.lastupdated); }   // alive, nothing shown changed
			}
		}
		var f = this.full[id];
		if (f && d.view === 'full' && (!d.rev || !f.rev || d.rev > f.rev)) {
			mergeAttributes(f, d.attributes);
			if (d.rev) { f.rev = d.rev; }
			f.lastupdated = d.lastupdated || f.lastupdated;
			if (d.updatecount != null) { f.updatecount = d.updatecount; }
			this.refreshBody(id, (d.attributes || []).map(function (a) { return a.name; }));
		}
	};

	ObjectList.prototype.touchAge = function (head, iso) {
		var n = head && head.querySelector(':scope > .age');
		var t = Date.parse(iso);
		if (n && !isNaN(t)) {
			n.setAttribute('data-t', String(t));
			n.title = 'Last update ' + iso;
			n.textContent = ageText(this.stream.serverNow() - t);
		}
	};

	function shallowAttr(a) {
		var v = a.value || {};
		var s = {};
		['kind', 'text', 'value', 'enum', 'units', 'ref', 'error'].forEach(function (k) { if (v[k] !== undefined) { s[k] = v[k]; } });
		var copy = {};
		Object.keys(a).forEach(function (k) { copy[k] = a[k]; });
		copy.value = s;
		return copy;
	}

	/**
	 * Re-renders an expanded object. With changedNames only those attribute rows are
	 * replaced (open sub-trees and raw views are kept); otherwise the whole body.
	 */
	ObjectList.prototype.refreshBody = function (id, changedNames) {
		var holders = [];
		if (this.rows[id]) { holders.push(this.rows[id]); }
		if (this.pinned === id) { holders.push(this.els.pinned.querySelector('.pin-card')); }
		var self = this, f = this.full[id];
		holders.forEach(function (h) {
			var body = h && h.querySelector(':scope > .obj-body');
			if (!body || !f) { return; }
			var table = body.querySelector('.at-table');
			if (!changedNames || !table) { self.fillBody(id, body); return; }
			changedNames.forEach(function (n) {
				var a = (f.attributes || []).filter(function (x) { return x.name === n; })[0];
				if (!a) { return; }
				var old = table.querySelector(':scope > .at-row[data-name="' + cssEscape(n) + '"]');
				var row = attrRow(a, old ? vv.openPathsIn(old) : new Set(), old ? old.classList.contains('raw-open') : false);
				if (old) { table.replaceChild(row, old); } else { table.appendChild(row); }
				self.flash(row);
			});
			var up = body.querySelector('.mi-updates');
			if (up && f.updatecount != null) { up.textContent = String(f.updatecount); }
			self.tickAges();
		});
	};

	function cssEscape(s) {
		return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/["\\]/g, '\\$&');
	}

	/** After a stream (re)start: the subscription is new, so reload the snapshot. */
	ObjectList.prototype.resync = function () {
		this.full = {};   // full views are refetched: they may have missed updates
		var p = this.pinned;
		this.reload();
		if (p) { this.pinned = null; this.pin({ instanceid: p, label: this.pinLabel || p, classname: this.pinClass || '' }); }
	};

	Rift.ObjectList = ObjectList;
})(window, document);
