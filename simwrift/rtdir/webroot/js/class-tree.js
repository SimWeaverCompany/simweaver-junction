/* ============================================================
   SimWeaver Rift - class tree (left panel).
   Built from the flat list of GET .../classes (only classes with
   instances); intermediate classes are added and counts summed up.

   var tree = new Rift.ClassTree(container, filterInput, { onChange: fn(selectedQnames) });
   tree.setClasses(classesReply)      // snapshot or 'classes' SSE event
   tree.clearSelection()
   Selection = set of qualified class names, each meaning "this class and its subclasses".
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;

	var ROOTS = ['HLAobjectRoot'];

	function ClassTree(container, filterInput, options) {
		this.box = container;
		this.filterInput = filterInput;
		this.opts = options || {};
		this.roots = [];
		this.byQ = {};
		this.selected = new Set();
		this.expanded = new Set();
		this.autoExpandDone = false;
		this.prevTotals = {};
		this.filterText = '';
		var self = this;
		if (filterInput) {
			filterInput.addEventListener('input', function () {
				self.filterText = filterInput.value.trim().toLowerCase();
				self.render();
			});
		}
	}

	/**
	 * Patch of topic "ds:<id>:classes": { "<classnameq>": count }. Counts are absolute;
	 * 0 means the class has no instances any more.
	 */
	ClassTree.prototype.applyCounts = function (patch) {
		var counts = this.counts || {};
		Object.keys(patch || {}).forEach(function (q) {
			var n = Number(patch[q]) || 0;
			if (n > 0) { counts[q] = n; } else { delete counts[q]; }
		});
		this.setClasses({ classes: Object.keys(counts).map(function (q) { return { classnameq: q, count: counts[q] }; }) });
	};

	ClassTree.prototype.setClasses = function (reply) {
		var list = (reply && Array.isArray(reply.classes)) ? reply.classes : [];
		var counts = {};
		list.forEach(function (c) { if (c && c.classnameq) { counts[String(c.classnameq)] = Number(c.count) || 0; } });
		this.counts = counts;
		var byQ = {}, roots = [];
		function node(q) {
			if (byQ[q]) { return byQ[q]; }
			var parts = q.split('.');
			var n = { q: q, name: parts[parts.length - 1], own: 0, total: 0, children: [] };
			byQ[q] = n;
			if (parts.length > 1) {
				var parent = node(parts.slice(0, -1).join('.'));
				parent.children.push(n);
			} else {
				roots.push(n);
			}
			return n;
		}
		list.forEach(function (c) {
			if (!c || !c.classnameq) { return; }
			node(String(c.classnameq)).own += Number(c.count) || 0;
		});
		function sum(n) {
			n.children.sort(function (a, b) { return a.name.localeCompare(b.name); });
			n.total = n.own + n.children.reduce(function (s, c) { return s + sum(c); }, 0);
			return n.total;
		}
		roots.forEach(sum);
		// Show the children of HLAobjectRoot as top level - the root itself adds nothing.
		var top = [];
		roots.forEach(function (r) {
			if (ROOTS.indexOf(r.q) !== -1 && r.own === 0) { top = top.concat(r.children); } else { top.push(r); }
		});
		top.sort(function (a, b) { return a.name.localeCompare(b.name); });

		this.changed = {};
		var prev = this.prevTotals, now = {};
		Object.keys(byQ).forEach(function (q) { now[q] = byQ[q].total; });
		if (Object.keys(prev).length) {
			Object.keys(now).forEach(function (q) { if (prev[q] !== now[q]) { this.changed[q] = true; } }, this);
		}
		this.prevTotals = now;
		this.byQ = byQ;
		this.roots = top;
		this.total = top.reduce(function (s, n) { return s + n.total; }, 0);

		// Drop selected classes that no longer exist.
		var self = this, lost = false;
		this.selected.forEach(function (q) { if (!byQ[q]) { self.selected.delete(q); lost = true; } });

		if (!this.autoExpandDone && top.length) {
			// First time: open the first three levels (RPR: BaseEntity > PhysicalEntity > Platform).
			var open = function (n, depth) {
				if (depth > 3) { return; }
				self.expanded.add(n.q);
				n.children.forEach(function (c) { open(c, depth + 1); });
			};
			top.forEach(function (n) { open(n, 1); });
			this.autoExpandDone = true;
		}
		this.render();
		if (lost && this.opts.onChange) { this.opts.onChange(this.selection()); }
	};

	/** @returns {string[]} selected qualified class names (minimal: no class below a selected one) */
	ClassTree.prototype.selection = function () {
		var sel = Array.from(this.selected);
		return sel.filter(function (q) {
			return !sel.some(function (o) { return o !== q && q.indexOf(o + '.') === 0; });
		});
	};

	ClassTree.prototype.isImplied = function (q) {
		var implied = false;
		this.selected.forEach(function (o) { if (q !== o && q.indexOf(o + '.') === 0) { implied = true; } });
		return implied;
	};

	ClassTree.prototype.toggleSelect = function (q) {
		if (this.selected.has(q)) { this.selected.delete(q); }
		else {
			// Selecting a class replaces selections below it.
			var self = this;
			Array.from(this.selected).forEach(function (o) { if (o.indexOf(q + '.') === 0) { self.selected.delete(o); } });
			this.selected.add(q);
		}
		this.render();
		if (this.opts.onChange) { this.opts.onChange(this.selection()); }
	};

	ClassTree.prototype.clearSelection = function () {
		if (!this.selected.size) { return; }
		this.selected.clear();
		this.render();
		if (this.opts.onChange) { this.opts.onChange([]); }
	};

	/** Selects exactly one class (e.g. from a class badge in the list). */
	ClassTree.prototype.selectOnly = function (q) {
		this.selected = new Set([q]);
		var parts = q.split('.');
		for (var i = 1; i < parts.length; i++) { this.expanded.add(parts.slice(0, i).join('.')); }
		this.render();
		if (this.opts.onChange) { this.opts.onChange(this.selection()); }
	};

	ClassTree.prototype.matchesFilter = function (n) {
		var f = this.filterText;
		if (!f) { return true; }
		if (n.name.toLowerCase().indexOf(f) !== -1) { return true; }
		return n.children.some(this.matchesFilter, this);
	};

	ClassTree.prototype.render = function () {
		var self = this;
		ui.clear(this.box);
		if (!this.roots.length) {
			this.box.appendChild(ui.emptyBlock('No object instances yet', 'Classes appear here as instances are discovered.'));
			return;
		}
		var rootList = el('ul.ct-list', { role: 'tree' });
		this.roots.forEach(function (n) { if (self.matchesFilter(n)) { rootList.appendChild(self.renderNode(n, 1)); } });
		if (!rootList.firstChild) {
			this.box.appendChild(el('div.ct-nomatch', { text: 'No class matches “' + this.filterText + '”.' }));
			return;
		}
		this.box.appendChild(rootList);
	};

	ClassTree.prototype.renderNode = function (n, depth) {
		var self = this;
		var kids = n.children.filter(this.matchesFilter, this);
		var open = this.filterText ? true : this.expanded.has(n.q);
		var selected = this.selected.has(n.q);
		var implied = !selected && this.isImplied(n.q);

		var row = el('div.ct-row' + (selected ? '.sel' : '') + (implied ? '.implied' : ''), {
			style: '--depth:' + depth
		}, [
			kids.length
				? el('button.ct-caret', {
					type: 'button', 'aria-label': (open ? 'Collapse ' : 'Expand ') + n.name, 'aria-expanded': open ? 'true' : 'false',
					on: { click: function () {
						if (self.expanded.has(n.q)) { self.expanded.delete(n.q); } else { self.expanded.add(n.q); }
						self.render();
					} }
				}, open ? '▾' : '▸')
				: el('span.ct-caret.leaf', { 'aria-hidden': 'true' }),
			el('button.ct-pick', {
				type: 'button',
				'aria-pressed': selected ? 'true' : 'false',
				title: (selected ? 'Remove filter: ' : 'Show only ') + n.q + ' (incl. subclasses)',
				on: { click: function () { self.toggleSelect(n.q); } }
			}, [
				el('span.ct-box', { 'aria-hidden': 'true' }),
				el('span.ct-name', { text: n.name }),
				el('span.ct-count' + (this.changed && this.changed[n.q] ? '.bump' : ''), {
					text: String(n.total),
					title: n.own + ' of this class, ' + (n.total - n.own) + ' in subclasses'
				})
			])
		]);
		var li = el('li', { role: 'treeitem', 'aria-expanded': kids.length ? (open ? 'true' : 'false') : null }, row);
		if (kids.length && open) {
			var ul = el('ul.ct-list', { role: 'group' });
			kids.forEach(function (c) { ul.appendChild(self.renderNode(c, depth + 1)); });
			li.appendChild(ul);
		}
		return li;
	};

	Rift.ClassTree = ClassTree;
})(window);
