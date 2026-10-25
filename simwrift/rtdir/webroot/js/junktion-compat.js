/* ============================================================
   SimWeaver Junktion - compatibility report dialog.
   For each direction between two endpoints: how many classes pass,
   what happens to the members (passed as is / object names rewritten /
   transcoded / dropped), and the lists behind the numbers.
   Rift.junktionCompat.openConfig(cfgId, name)  - from the FOMs, no connection
   Rift.junktionCompat.openGateway(gid, name)   - the running gateway's tables
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;
	var jx = Rift.junktion;

	var dialog, body, title, ready = false;

	function listBlock(label, items, total) {
		var n = Array.isArray(items) ? items.length : Object.keys(items || {}).length;
		if (!n) { return null; }
		var rows = Array.isArray(items)
			? items.map(function (s) { return el('li', { text: s }); })
			: Object.keys(items).map(function (k) { return el('li', null, [el('code', { text: k }), el('span.dim', { text: '  ' + items[k] })]); });
		return el('details.jx-compat-list', null, [
			el('summary', { text: label + ' · ' + (total !== undefined && total > n ? n + ' of ' + total : n) }),
			el('ul', null, rows)
		]);
	}

	function stat(k, v, cls) {
		return el('span.jx-stat' + (cls ? '.' + cls : ''), null, [el('span.k', { text: k }), el('span.v', { text: String(v || 0) })]);
	}

	function routeBlock(r) {
		var rep = r.report || {};
		var m = rep.members || {};
		return el('section.jx-route', null, [
			el('header.jx-route-head', null, [
				el('span.jx-route-dir', null, [el('b', { text: r.from }), el('span.jx-route-arrow', { text: ' ━━▶ ' }), el('b', { text: r.to })]),
				el('span.dim', { text: r.conversion || '' })
			]),
			el('div.jx-gw-stats', null, [
				stat('OBJECT CLASSES', rep.objectclasses),
				stat('INTERACTIONS', rep.interactionclasses),
				stat('AS IS', m.pass, 'ok'),
				stat('NAMES REWRITTEN', m.rewritereferences),
				stat('TRANSCODED', m.transcode, m.transcode ? 'note' : null),
				stat('MEMBERS DROPPED', m.dropped, m.dropped ? 'warn' : null)
			]),
			listBlock('Promoted to a superclass', rep.promoted),
			listBlock('Transcoded members', rep.transcodedmembers),
			listBlock('Dropped members', rep.droppedmembers, m.dropped),
			listBlock('No counterpart on the other side', rep.nocounterpart, rep.nocounterpartcount),
			listBlock('Blocked by the filters', rep.blocked, rep.blockedcount)
		]);
	}

	function show(name, loader) {
		init();
		title.textContent = 'Compatibility · ' + name;
		ui.clear(body).appendChild(ui.loadingRow('Comparing the information models…'));
		if (typeof dialog.showModal === 'function') { dialog.showModal(); } else { dialog.setAttribute('open', ''); }
		loader().then(function (d) {
			ui.clear(body);
			var errors = d.errors || {};
			Object.keys(errors).forEach(function (ep) {
				body.appendChild(ui.errorRow('Model of endpoint ' + ep + ' could not be loaded', new Error(errors[ep])));
			});
			var routes = d.routes || [];
			if (!routes.length) { body.appendChild(ui.emptyBlock('No routes', 'No two endpoints could be compared.')); return; }
			body.appendChild(el('p.hint', { text: 'For each direction: what passes as is, what is converted, and what is left out. ' +
				'Open a list for the details.' }));
			routes.forEach(function (r) { body.appendChild(routeBlock(r)); });
		}, function (err) {
			ui.clear(body).appendChild(ui.errorRow('Could not compute the compatibility', err));
		});
	}

	function close() {
		if (typeof dialog.close === 'function') { dialog.close(); } else { dialog.removeAttribute('open'); }
	}

	function init() {
		if (ready) { return; }
		ready = true;
		dialog = document.getElementById('compat-dialog');
		body = document.getElementById('cp-body');
		title = document.getElementById('cp-title');
		document.getElementById('cp-close').addEventListener('click', close);
		document.getElementById('cp-ok').addEventListener('click', close);
	}

	Rift.junktionCompat = {
		openConfig: function (cfgId, name) { show(name || cfgId, function () { return jx.fetchCompatibility(cfgId); }); },
		openGateway: function (gid, name) { show(name || gid, function () { return jx.fetchGatewayCompatibility(gid); }); }
	};
})(window, document);
