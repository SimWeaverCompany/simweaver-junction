/* ============================================================
   SimWeaver Rift - index page (operations overview).
   1. Available datasource configurations  (GET /api/datasourceconfigs)
   2. Running datasources                  (GET /api/datasources)
   ============================================================ */

(function (window, document) {
	'use strict';

	var Rift = window.Rift;
	var ui = Rift.ui;
	var el = ui.el;

	/** Page that shows one running datasource. */
	var DETAIL_PAGE = 'datasource.html';

	/* ---------- Helpers ---------- */

	function setMeta(id, countText) {
		var node = document.getElementById(id);
		if (!node) { return; }
		ui.clear(node).appendChild(document.createTextNode(countText ? countText + ' · ' : ''));
		node.appendChild(el('span.upd', { text: 'UPD ' + ui.timeZ() }));
	}

	function plural(n, one, many) { return n + ' ' + (n === 1 ? one : many); }

	/* ---------- Available configurations ---------- */

	function configCard(cfg) {
		return Rift.configCard.render(cfg, { actions: [
			el('button.primary', {
				type: 'button',
				title: 'Create a new datasource from ' + (cfg.name || 'this configuration'),
				on: { click: function () { Rift.createDialog.open(cfg, onDatasourceCreated); } }
			}, '\u25B6 Create datasource')
		] });
	}

	/* Configurations made by the server from Junction gateway endpoints (etc/junctions): in a
	   group of their own, collapsed unless the user opened it (remembered in this browser). */
	var GATEWAY_KEY = 'rift-index-gateway-configs';

	function gatewayConfigs(list, onlyThese) {
		var shown = onlyThese;
		try {
			var v = window.localStorage.getItem(GATEWAY_KEY);
			if (v === 'shown') { shown = true; } else if (v === 'hidden') { shown = false; }
		} catch (e) { /* storage blocked */ }
		var grid = el('div.card-grid', { hidden: !shown });
		list.forEach(function (cfg) { grid.appendChild(configCard(cfg)); });
		var btn = el('button.btn', { type: 'button', 'aria-expanded': shown ? 'true' : 'false' });
		var label = function () { btn.textContent = grid.hidden ? '▸ Show' : '▾ Hide'; btn.setAttribute('aria-expanded', grid.hidden ? 'false' : 'true'); };
		label();
		btn.addEventListener('click', function () {
			grid.hidden = !grid.hidden;
			label();
			try { window.localStorage.setItem(GATEWAY_KEY, grid.hidden ? 'hidden' : 'shown'); } catch (e) { /* storage blocked */ }
		});
		return el('section.cfg-group', null, [
			el('div.cfg-group-head', null, [
				el('span.origin-chip', { text: 'JUNCTION' }),
				el('span.cfg-group-title', { text: plural(list.length, 'configuration', 'configurations') + ' from Junction gateway endpoints' }),
				el('span.cfg-group-about', { text: 'Made by the server from the gateway files in etc/junctions (one per endpoint), not saved in this browser. ' +
					'They go when their gateway file is removed; the server setting junction.datasourceconfigs=false leaves them out of this list.' }),
				btn
			]),
			grid
		]);
	}

	function loadConfigs() {
		var target = document.getElementById('config-list');
		ui.clear(target).appendChild(ui.loadingRow('Acquiring datasource configurations…'));

		var request = Rift.api.fetchDatasourceConfigs().then(function (configs) {
			ui.clear(target);
			setMeta('config-meta', plural(configs.length, 'CONFIG', 'CONFIGS'));
			if (configs.length === 0) {
				target.appendChild(ui.emptyBlock(
					'No datasource configurations available',
					'Add *.datasource.settings files on the server and reload.'));
				return;
			}
			var own = configs.filter(function (c) { return !c.origin; });
			var fromGateways = configs.filter(function (c) { return !!c.origin; });
			if (own.length) {
				var grid = el('div.card-grid');
				own.forEach(function (cfg) { grid.appendChild(configCard(cfg)); });
				target.appendChild(grid);
			}
			if (fromGateways.length) { target.appendChild(gatewayConfigs(fromGateways, !own.length)); }
		}, function (err) {
			ui.clear(target).appendChild(ui.errorRow('Could not load datasource configurations', err, loadConfigs));
			setMeta('config-meta', '');
		});

		return ui.busy(document.getElementById('btn-reload-configs'), request);
	}

	/* ---------- Running datasources ---------- */

	function runningItem(ds) {
		var href = DETAIL_PAGE + '?id=' + encodeURIComponent(ds.id);
		var name = ds.name || '(unnamed)';
		// Link and button are siblings: a <button> may not live inside an <a>.
		return el('li.run-row', null, [
			el('a.run-item', { href: href, 'data-id': ds.id }, [
				el('span.lamp', { 'aria-hidden': 'true' }),
				el('span.run-name', { text: name }),
				el('span.run-state', { text: 'ACTIVE' }),
				el('span.run-open', { 'aria-hidden': 'true', text: 'OPEN ›' })
			]),
			el('button.run-remove', {
				type: 'button',
				title: 'Disconnect and remove ' + name,
				'aria-label': 'Remove ' + name,
				on: { click: function () { Rift.removeDatasource.confirm(ds, onDatasourceRemoved); } }
			}, '✕ Remove')
		]);
	}

	/** After a remove: refresh the running list. */
	function onDatasourceRemoved() {
		loadRunning();
	}

	function loadRunning() {
		var target = document.getElementById('running-list');
		ui.clear(target).appendChild(ui.loadingRow('Polling running datasources…'));

		var request = Rift.api.fetchDatasources().then(function (list) {
			ui.clear(target);
			setMeta('running-meta', plural(list.length, 'ACTIVE', 'ACTIVE'));
			if (list.length === 0) {
				target.appendChild(ui.emptyBlock(
					'No datasources are running',
					'All quiet. Started datasources will report in here.'));
				return;
			}
			var ul = el('ul.run-list');
			list.forEach(function (ds) { ul.appendChild(runningItem(ds)); });
			target.appendChild(ul);
		}, function (err) {
			ui.clear(target).appendChild(ui.errorRow('Could not load running datasources', err, loadRunning));
			setMeta('running-meta', '');
		});

		return ui.busy(document.getElementById('btn-reload-running'), request);
	}

	/** After a create: refresh the running list and flash the new entry. */
	function onDatasourceCreated(created) {
		loadRunning().then(function () {
			if (!created || created.id === undefined) { return; }
			var items = document.querySelectorAll('#running-list .run-item');
			Array.prototype.forEach.call(items, function (a) {
				if (a.getAttribute('data-id') === String(created.id)) {
					a.classList.add('fresh');
					if (a.scrollIntoView) { a.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }
				}
			});
		});
	}

	/* ---------- Start-up ---------- */

	document.addEventListener('DOMContentLoaded', function () {
		ui.startClock();
		ui.startLinkIndicator();
		ui.showFlash();   // e.g. "removed" message after coming back from the detail page

		document.getElementById('btn-reload-configs').addEventListener('click', loadConfigs);
		document.getElementById('btn-reload-running').addEventListener('click', loadRunning);

		loadConfigs();
		loadRunning();
	});

	// Exposed for later use (e.g. refresh on SSE events).
	Rift.index = { loadConfigs: loadConfigs, loadRunning: loadRunning };
})(window, document);
