/* ============================================================
   SimWeaver - "Saved in this browser": what the pages keep in this
   browser (localStorage of this server's address), and buttons to
   clear it. Adds a button to the page footer of every page that
   loads this file (after ui.js).

   Nothing here is on the server: clearing affects this browser only.
   Datasource configurations, gateways and maps all come from the
   server and are not stored here.
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;

	/* What the pages store, by key (exact) or key prefix (ending in '*'). */
	var KINDS = [
		{ key: 'rift-map-background', label: 'Background map choice',
			about: 'The map you picked in the map view, for every datasource. Cleared: the server\'s default map is shown.' },
		{ key: 'rift-map:*', label: 'Map views',
			about: 'Where each datasource\'s map was looking (position, zoom) and the label setting.' },
		{ key: 'rift-map-shape-overlays', label: 'Map overlays',
			about: 'Which shapefile overlays of the server you turned on or off.' },
		{ key: 'rift-map-overlays', label: 'Map layers',
			about: 'Fire and detonation flashes, laser designators, radar and jammer beams, and the beam length: on or off.' },
		{ key: 'rift-ds-view', label: 'List or map',
			about: 'Whether the datasource page opens with the object list or the map.' },
		{ key: 'rift-index-gateway-configs', label: 'Gateway configurations shown',
			about: 'Whether the datasource list shows the configurations made from Junction gateway endpoints.' },
		{ key: 'simweaver-config-assistant-v1', label: 'Config Assistant',
			about: 'Everything filled in on the Config Assistant page, both sections.' },
		{ key: 'simweaver-rift-theme', label: 'Day or night',
			about: 'The colour theme.' }
	];

	function storage() {
		try { return window.localStorage; } catch (e) { return null; }
	}

	function matches(kind, key) {
		return kind.key.slice(-1) === '*' ? key.indexOf(kind.key.slice(0, -1)) === 0 : key === kind.key;
	}

	/** [{kind, keys[], bytes}] for what is stored now, plus one "Other" entry for unknown keys. */
	function inventory() {
		var ls = storage();
		var rows = KINDS.map(function (k) { return { kind: k, keys: [], bytes: 0 }; });
		var other = { kind: { label: 'Other', about: 'Saved by this site but not known to this page (an older or newer version).' }, keys: [], bytes: 0 };
		if (!ls) { return { rows: [], blocked: true }; }
		for (var i = 0; i < ls.length; i++) {
			var key = ls.key(i);
			if (key == null) { continue; }
			var v = ls.getItem(key) || '';
			var row = null;
			for (var j = 0; j < rows.length && !row; j++) { if (matches(rows[j].kind, key)) { row = rows[j]; } }
			row = row || other;
			row.keys.push(key);
			row.bytes += (key.length + v.length) * 2;
		}
		if (other.keys.length) { rows.push(other); }
		return { rows: rows.filter(function (r) { return r.keys.length; }), blocked: false };
	}

	function size(bytes) {
		return bytes < 1024 ? bytes + ' B' : (bytes / 1024).toFixed(bytes < 10240 ? 1 : 0) + ' kB';
	}

	function removeKeys(keys) {
		var ls = storage();
		if (!ls) { return; }
		keys.forEach(function (k) { try { ls.removeItem(k); } catch (e) { /* ignore */ } });
	}

	var dialog = null, parts = {}, changed = false;

	function build() {
		parts.close = el('button.btn', { type: 'button', 'aria-label': 'Close', on: { click: close } }, '✕');
		parts.list = el('div.bd-list');
		parts.reload = el('button.btn', { type: 'button', hidden: true, on: { click: function () { window.location.reload(); } } }, '⟳ Reload page');
		parts.all = el('button.danger', { type: 'button', on: { click: clearAll } }, 'Clear all');
		dialog = el('dialog.dialog.bd-dialog', { id: 'browser-data-dialog', 'aria-labelledby': 'bd-title' },
			el('form', { method: 'dialog', on: { submit: function (e) { e.preventDefault(); close(); } } }, [
				el('div.panel-head', null, [el('span.idx', { text: 'LOCAL' }),
					el('h2.title', { id: 'bd-title', text: 'Saved in this browser' }), el('span.meta', null, parts.close)]),
				el('div.dialog-body', null, [
					el('p.bd-lead', { text: 'These pages remember a few choices in this browser, for this server address only. ' +
						'Clearing them changes nothing on the server and nothing for other users.' }),
					parts.list,
					el('p.bd-note', { text: 'Not stored here: datasource configurations (including those made from Junction gateways), ' +
						'gateways and maps all come from the server. Map tiles the browser keeps are replaced on their own when the ' +
						'server\'s map file changes.' })
				]),
				el('div.dialog-foot', null, [parts.reload, el('span.bd-gap'), el('button.btn', { type: 'button', on: { click: close } }, 'Close'), parts.all])
			]));
		dialog.addEventListener('click', function (e) { if (e.target === dialog) { close(); } });
		document.body.appendChild(dialog);
	}

	function render() {
		var inv = inventory();
		ui.clear(parts.list);
		if (inv.blocked) {
			parts.list.appendChild(el('p.bd-empty', { text: 'This browser does not let the pages store anything (private window or blocked site data): nothing is saved.' }));
		} else if (!inv.rows.length) {
			parts.list.appendChild(el('p.bd-empty', { text: 'Nothing is saved in this browser.' }));
		}
		inv.rows.forEach(function (r) {
			var n = r.keys.length;
			parts.list.appendChild(el('div.bd-row', null, [
				el('div.bd-text', null, [
					el('div.bd-label', null, [r.kind.label, el('span.bd-size', { text: (n > 1 ? n + ' entries · ' : '') + size(r.bytes) })]),
					el('div.bd-about', { text: r.kind.about })
				]),
				el('button.btn', { type: 'button', title: r.keys.join('\n'), on: { click: function () {
					removeKeys(r.keys);
					changed = true;
					render();
				} } }, 'Clear')
			]));
		});
		parts.all.disabled = !inv.rows.length;
		parts.reload.hidden = !changed;
	}

	function clearAll() {
		var inv = inventory();
		inv.rows.forEach(function (r) { removeKeys(r.keys); });
		window.location.reload();                     // the page itself may hold the old values
	}

	function open() {
		if (!dialog) { build(); }
		changed = false;
		render();
		if (typeof dialog.showModal === 'function') { dialog.showModal(); } else { dialog.setAttribute('open', ''); }
	}

	function close() {
		if (!dialog) { return; }
		if (typeof dialog.close === 'function') { dialog.close(); } else { dialog.removeAttribute('open'); }
		if (changed) { ui.toast('Cleared in this browser. Reload the page to see the effect everywhere.', 'info', 5000); }
	}

	document.addEventListener('DOMContentLoaded', function () {
		var foot = document.querySelector('[data-browser-data]') || document.querySelector('footer.pagefoot');
		if (!foot) { return; }
		foot.appendChild(el('button.foot-btn', { type: 'button', title: 'What these pages remember in this browser, and clearing it',
			on: { click: open } }, 'Saved in this browser…'));
	});

	Rift.browserData = { open: open, inventory: inventory, kinds: KINDS };
})(window, document);
