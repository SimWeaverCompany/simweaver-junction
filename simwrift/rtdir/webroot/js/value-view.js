/* ============================================================
   SimWeaver Rift - renders value nodes (DecodedValue as JSON,
   contract 3.3/3.4).

   Rift.valueView.inline(node)          one line: text + units + object chips
   Rift.valueView.tree(node, name, path, openPaths)
                                        expandable tree (<details>) of fields /
                                        items / derived, for the full view
   Rift.valueView.chips(ref)            object chips for a ref
   Rift.valueView.entityType(et, compact) an entity type in words (0.11.1, SISO-REF-010)
   Rift.valueView.onSelect = fn(target) set by the page: called when a chip is clicked
   ============================================================ */

(function (window) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;

	var view = { onSelect: null };

	function nodeText(node) {
		if (!node || typeof node !== 'object') { return node == null ? '' : String(node); }
		if (node.text !== undefined && node.text !== null) { return String(node.text); }
		if (node.value !== undefined && node.value !== null) { return String(node.value); }
		return '';
	}

	/** Object chips for a ref: resolved targets are buttons, unresolved keys are dim. */
	function chips(ref, max) {
		if (!ref || !Array.isArray(ref.keys)) { return null; }
		var targets = Array.isArray(ref.targets) ? ref.targets : [];
		var wrap = el('span.chips');
		max = max || 12;
		ref.keys.slice(0, max).forEach(function (key, i) {
			var t = targets[i];
			if (t && t.instanceid) {
				wrap.appendChild(el('button.chip', {
					type: 'button',
					title: t.classname + ' · ' + key + ' (' + ref.kind + ')',
					'data-iid': t.instanceid,
					on: { click: function (e) {
						e.stopPropagation();
						if (view.onSelect) { view.onSelect(t); }
					} }
				}, [el('span.chip-dot', { 'aria-hidden': 'true' }), t.label || t.instanceid,
					el('span.chip-class', { text: t.classname })]));
			} else {
				wrap.appendChild(el('span.chip.unresolved', {
					title: 'Not discovered (yet): ' + key + ' (' + ref.kind + ')'
				}, [el('span.chip-dot', { 'aria-hidden': 'true' }), key]));
			}
		});
		if (ref.keys.length > max) {
			wrap.appendChild(el('span.chip.more', { text: '+' + (ref.keys.length - max) }));
		}
		return wrap;
	}

	/**
	 * One-line rendering: the node's text, units, error flag and chips.
	 * @param {Object} node
	 * @param {number} [maxChips] chips shown before "+N" (lists use 3)
	 */
	function inline(node, maxChips, noEntityType) {
		node = node || {};
		var text = nodeText(node);
		var wrap = el('span.v-inline' + (node.error ? '.has-error' : ''), { title: node.error ? 'Decode error: ' + node.error : null });
		if (node.kind === 'EMPTY' || text === '') {
			wrap.appendChild(el('span.v-none', { text: node.kind === 'EMPTY' ? 'empty' : '—' }));
		} else if (node.ref && node.ref.targets && node.ref.targets.some(function (t) { return t; })) {
			// a resolved reference: the chips say it better than the raw key
		} else {
			wrap.appendChild(el('span.v-text.k-' + String(node.kind || 'x').toLowerCase(), { text: text }));
		}
		if (node.units) { wrap.appendChild(el('span.v-units', { text: node.units })); }
		if (node.truncated) { wrap.appendChild(el('span.v-flag', { text: 'cut', title: 'Value truncated by the server' })); }
		if (node.error) { wrap.appendChild(el('span.v-flag.err', { text: 'ERR' })); }
		if (node.ref) { wrap.appendChild(chips(node.ref, maxChips)); }
		if (node.entitytype && !noEntityType) { wrap.appendChild(entityType(node.entitytype, true)); }
		return wrap;
	}

	/**
	 * 0.11.1: an entity type in words (the server's node.entitytype, from SISO-REF-010): the
	 * country code, the most specific name and (unless compact) the broader one. The tooltip
	 * has the whole chain.
	 */
	function entityType(et, compact) {
		if (!et || !et.text) { return null; }
		var tip = [];
		if (et.path && et.path.length) {
			tip.push(et.path.map(function (p) { return p.name; }).join(' \u203A '));
		} else {
			tip.push((et.countryname ? et.countryname + ' \u00B7 ' : '') + et.text + (et.context ? ' \u00B7 ' + et.context : ''));
		}
		if (!et.complete) { tip.push('Only partly in ' + (et.source || 'SISO-REF-010') + ': the deeper levels are not listed there'); }
		tip.push('Names from ' + (et.source || 'SISO-REF-010') + ', reprinted with permission from SISO Inc.');
		return el('span.etype' + (compact ? '.compact' : '') + (et.complete ? '' : '.partial'), { title: tip.join('\n') }, [
			et.country ? el('span.etype-cc', { text: et.country, 'aria-label': et.countryname || et.country }) : null,
			el('span.etype-text', { text: (et.complete ? '' : '\u2248 ') + et.text }),
			!compact && et.context ? el('span.etype-ctx', { text: et.context }) : null
		]);
	}

	function hasChildren(node) {
		return node && ((node.fields && node.fields.length) || (node.items && node.items.length) ||
			(node.derived && node.derived.length));
	}

	/**
	 * Expandable tree. openPaths: Set of paths that are open (kept across re-renders).
	 * @returns {Node}
	 */
	function tree(node, name, path, openPaths) {
		node = node || {};
		var head = el('span.vt-head', null, [
			name !== null && name !== undefined ? el('span.vt-name', { text: name }) : null,
			node.type ? el('span.vt-type', { text: node.type }) : null,
			inline(node, undefined, true)
		]);
		// 0.11.1: an entity type gets its name on the line below the numbers
		var et = node.entitytype ? el('div.vt-etype', null, entityType(node.entitytype, false)) : null;
		if (!hasChildren(node)) {
			return el('div.vt-leaf', null, [head, et, node.error ? el('div.vt-error', { text: node.error }) : null]);
		}
		var details = el('details.vt-node', { 'data-path': path });
		if (openPaths && openPaths.has(path)) { details.open = true; }
		details.appendChild(el('summary', null, [head, et]));
		var body = el('div.vt-body');
		(node.fields || []).forEach(function (f) {
			body.appendChild(tree(f, f.name, path + '.' + f.name, openPaths));
		});
		if (node.items && node.items.length) {
			node.items.forEach(function (it, i) {
				body.appendChild(tree(it, '[' + i + ']', path + '[' + i + ']', openPaths));
			});
			if (node.count !== undefined && node.count > node.items.length) {
				body.appendChild(el('div.vt-more', { text: '… ' + (node.count - node.items.length) + ' more of ' + node.count }));
			}
		}
		if (node.derived && node.derived.length) {
			var der = el('div.vt-derived');
			der.appendChild(el('div.vt-derived-head', { text: 'derived' }));
			node.derived.forEach(function (d) {
				der.appendChild(tree(d, d.name, path + '~' + d.name, openPaths));
			});
			body.appendChild(der);
		}
		if (node.error) { body.appendChild(el('div.vt-error', { text: node.error })); }
		(node.warnings || []).forEach(function (w) { body.appendChild(el('div.vt-warn', { text: w })); });
		details.appendChild(body);
		return details;
	}

	/** Paths of all open <details> below root (to restore after a re-render). */
	function openPathsIn(root) {
		var set = new Set();
		Array.prototype.forEach.call(root.querySelectorAll('details.vt-node[open]'), function (d) {
			set.add(d.getAttribute('data-path'));
		});
		return set;
	}

	/** Every ref target found anywhere in a node tree. */
	function collectTargets(node, out) {
		out = out || [];
		if (!node || typeof node !== 'object') { return out; }
		if (node.ref && Array.isArray(node.ref.targets)) {
			node.ref.targets.forEach(function (t) { if (t) { out.push(t); } });
		}
		(node.fields || []).forEach(function (f) { collectTargets(f, out); });
		(node.items || []).forEach(function (f) { collectTargets(f, out); });
		return out;
	}

	view.text = nodeText;
	view.inline = inline;
	view.entityType = entityType;
	view.tree = tree;
	view.chips = chips;
	view.openPathsIn = openPathsIn;
	view.collectTargets = collectTargets;
	Rift.valueView = view;
})(window);
